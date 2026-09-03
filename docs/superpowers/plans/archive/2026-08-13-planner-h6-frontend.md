# Planner-Executor H-6 Frontend + Composer UX Implementation Plan

> **状态**：✅ 已完成（2026-08-13）  
> **Spec**：[rebuild §4 / H-6](../../specs/archive/2026-08-05-planner-executor-rebuild-design.md) · [routing v6](../../specs/archive/2026-07-29-unified-routing-design.md)（`kind` ⊥ `executionMode`）  
> **前置**：H-0～H-5 ✅（[kernel](./2026-08-13-planner-executor-kernel.md) · [routing v6+H-5](./2026-08-13-unified-routing-v6-h5.md)）  
> **本 plan 不做（仍延期）**：H-7 全量 Live P1–P8；阶段 D / R-4 删 `PlanWorkflow*` 后端源码；Decision D12 Planner；压缩点 / task-scene 深改  
>
> **Commits（T1–T6）**：`f9d9a6e1` composer · `a80c06fc` harness vs DAG · `f8d3e7ce` OperationStack hierarchy · `0f5f008b` TaskBoard · `50fd83d9` disconnect Approval · `6de8b35d` interleave  
> **Follow-up**：harness 一级 TaskBoard 需后端 H1 `tasks` SSE（现仅有 plan/worker 步；见风险 §1）  

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 专业模式（`pro` / harness）消息用**分层普通时间线 + 一/二级 TaskBoard**展示（无 Plan DAG、无 Approval UI）；Composer：`kind=task` 与 chat 一样可选 `fast|pro|workflow`；分支选择器移到输入框下方；去掉「AI 生成内容」提示。

**Architecture:** 复用 `OperationStack` / `TaskBoardPanel` / `SubStepsFold`。用步骤契约区分：**有 Plan DAG 图数据** → 静态/旧动态 plan 仍走 `PlanWorkflowPanel`；**harness 步**（`plan`/`plan-R*` + `worker-*`，无 `planGraph`）→ 行式层级时间线。Composer 工具栏始终挂 `ExecutionModeSelector`；task 会话在输入框**下方**独立 subbar 放 `GitBranchSelector`（对齐 Cursor 底栏分支位）。

**Tech Stack:** Vue3 + Naive UI · 现有 `processingSteps` / SSE · Vitest  

## Global Constraints

- **SSOT**：时间线/看板以 [rebuild §4](../../specs/archive/2026-08-05-planner-executor-rebuild-design.md) 为准；模式以 routing v6 为准
- **`kind` ⊥ `executionMode`**：task 会话**必须**能选三模式；禁止「task 只能选分支、不能选模式」
- **禁止**把 harness 时间线做成步骤卡片墙 / `WorkerCard`
- **禁止** TaskBoard mini-DAG / 依赖边（D11）
- **禁止**删除 `CollapsibleConfirmPanel`（HITL/Recovery 共用）
- **禁止阶段 D**：可不渲染 Approval / 动态 plan 分支，但**不要**删后端 `PlanWorkflow*`；历史带 `planGraph` 的消息仍可 DAG 展示
- **静态 Workflow（`executionMode=workflow`）** 继续 DAG（D3）
- UI：**禁止**冗余解释性文案；去掉 AI 生成免责提示
- 背景 `--sun-black`、边框分区；勿用 `--sun-surface` 灰底铺页面
- 前端：`cd sunshine-ui && npx vitest run <spec>`；改动涉及 Chat 时手动验 composer

---

## File map

| 文件 | 职责 |
|------|------|
| `sunshine-ui/src/views/ChatView.vue` | Composer 布局：模式常驻；分支 subbar；删 hint |
| `sunshine-ui/src/components/chat/GitBranchSelector.vue` | 样式适配下方 subbar（如需） |
| `sunshine-ui/src/components/operation/OperationStack.vue` | harness 层级：plan→worker→subSteps/handoff；`showPlanDag` 收紧 |
| `sunshine-ui/src/components/operation/TaskBoardPanel.vue` | 一级 H1 波次 + 条件二级 todolist |
| `sunshine-ui/src/api/processingSteps.ts` / display helpers | harness 步识别、`worker-*` / handoff |
| `sunshine-ui/src/api/contentInterleave.ts` | harness 与旧 plan-workflow 穿插分支 |
| `sunshine-ui/src/components/plan/PlanWorkflowPanel.vue` | 仅静态/有图 DAG；断开 Approval 绑定 |
| `sunshine-ui/src/components/plan/PlanApprovalActions.vue` | 停止挂到主路径（可留文件至阶段 D） |
| `scripts/verify_*`（可选） | 视觉/冒烟不强制本 plan |

---

## 现状锚点（实现前必读）

1. **Composer 互斥 bug（要修）** — `ChatView.vue` ~2081–2094：

```vue
<template v-if="… isCurrentTask …">
  <GitBranchSelector … />
</template>
<template v-else-if="!(newTaskMode || pendingWorkspace)">
  <ExecutionModeSelector … />
</template>
```

task 时**只显示分支、隐藏三模式** → 违反 `kind` ⊥ `executionMode`。

2. **AI 提示（要删）** — `ChatView.vue` ~2157：

```html
<p class="composer-hint">AI 生成内容仅供参考，请核实重要信息</p>
```

3. **DAG 门控** — `OperationStack.showPlanDag`：有 `planId` / `planApproval.planGraph` / `executionPlanId` 即开 DAG。harness 的 `plan`/`plan-R*` **无图**时应为 false，走普通时间线。

4. **后端 harness SSE（已有）**：`plan` / `plan-R{n}`、`worker-{taskId}`、最终 content；Round / H1 在 Redis。前端需识别并挂层级，而非等新 SSE 协议（若缺 handoff 子行 metadata，用 worker `summary`/`result` 收束行）。

---

### Task 1: Composer — task 三模式 + 分支下移 + 去 AI 提示

**Files:**
- Modify: `sunshine-ui/src/views/ChatView.vue`
- Modify: 相关 scoped CSS（`.composer-hint` 删除；新增 `.composer-subbar`）
- Test: 可选 `ChatView` 不强制单测；加 `composerLayout` 注释验收清单于 PR/report

**目标布局：**

```
┌─ composer-box ─────────────────────────┐
│  [输入框 ComposerSkillInput]            │
│  toolbar: ModeSelector | Kb | Model Send│
└────────────────────────────────────────┘
  subbar (仅 task / newTask / pendingWs):
  [ GitBranchSelector 分支 ▾ ]     ← 输入框下方，对齐参考图底栏分支位
  （无 AI 提示行）
```

- [ ] **Step 1: 改模板**

1. `composer-toolbar-left`：**始终**渲染 `ExecutionModeSelector`（chat 与 task；`newTaskMode`/`pendingWorkspace` 同样显示，因创建后即带 mode）。
2. **从 toolbar 移除** `GitBranchSelector`。
3. 在 `composer-box` **闭合之后**（或 box 内底边外侧）新增：

```vue
<div
  v-if="chatStore.newTaskMode || chatStore.pendingWorkspace || (isCurrentTask && currentWorkspaceId)"
  class="composer-subbar"
>
  <GitBranchSelector
    :workspace-id="…"
    :model-value="taskBranch"
    :active-branch="taskActiveBranch"
    :create-mode="…"
    @update:model-value="taskBranch = $event"
  />
</div>
```

4. **删除** `<p class="composer-hint">AI 生成内容…</p>` 及 `.composer-hint` 样式。

- [ ] **Step 2: 样式**

`.composer-subbar`：横向、左对齐、与 composer 同宽内边距、矮行（参考 Cursor 底栏密度）；勿用卡片灰底。

- [ ] **Step 3: 发送路径确认**

task 会话 `handleSend` 已带 `executionPreference`/`executionMode` → 后端；若 task 路径曾硬编码跳过 preference，改为与 chat 一致传 `preference`。

- [ ] **Step 4: 手工验收清单**

- [ ] chat：三模式可见；无分支 subbar；无 AI 提示  
- [ ] task：三模式可见；分支在输入框**下方**；无 AI 提示  
- [ ] task + `pro` 发送：请求体 `executionMode=pro`（或 preference 映射）

- [ ] **Step 5: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(ui): task composer keeps modes; branch under input

EOF
)"
```

---

### Task 2: 识别 harness 时间线 vs Plan DAG

**Files:**
- Modify: `sunshine-ui/src/api/processingSteps.ts`（或新建 `harnessTimeline.ts`）
- Test: `sunshine-ui/src/api/harnessTimeline.spec.ts`

**Interfaces:**

```ts
/** 有可渲染 DAG 图 → 静态/旧 plan-workflow */
export function isPlanDagMessage(steps: ProcessingStep[], executionPlanId?: string | null): boolean

/** harness：存在 plan 步且无 DAG 图，或存在 worker-* / phase=worker */
export function isHarnessTimelineMessage(steps: ProcessingStep[]): boolean
```

规则（写死在测里）：
- `isPlanDagMessage`：任一 plan 步含 `metadata.planApproval.planGraph.nodes` 或可解析 planId 且非纯 harness 标记 / 或 `executionPlanId` 且节点步 `node-*` 为主
- `isHarnessTimelineMessage`：`steps.some(s => s.phase==='worker' || /^worker-/.test(s.id))` **或**（有 `phase==='plan'` 且 **无** planGraph）
- 二者互斥优先：有真 DAG 图 → DAG；否则 harness/ReAct

- [ ] **Step 1: 写失败测** — 构造 harness steps vs planGraph steps

- [ ] **Step 2: 实现 + `OperationStack.showPlanDag` 改用 `isPlanDagMessage`**

- [ ] **Step 3: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(ui): distinguish harness timeline from plan DAG

EOF
)"
```

---

### Task 3: OperationStack harness 层级（plan → worker → handoff）

**Files:**
- Modify: `OperationStack.vue`
- Modify: display/grouping helpers as needed
- Test: unit for row grouping if extracted; else component-level logic test

**行为（rebuild §4.1）：**

```
intent?
└─ plan / plan-R{n}
   └─ worker-{taskId}          （缩进一级）
      ├─ subSteps (think/tool) （SubStepsFold）
      └─ handoff 摘要行        （worker 结束 summary/result）
└─ planner-answer / 正文 content（既有穿插）
```

- [ ] **Step 1: displaySteps** — harness 消息不过滤掉 `worker-*`；不套 `PlanWorkflowPanel`

- [ ] **Step 2: 行渲染** — `worker-*` 挂在最近 `plan*` 下（视觉 indent）；无 plan 时 worker 仍顶级显示

- [ ] **Step 3: handoff** — worker `lifecycle=done` 时用 `summary`/`detail`/`result` 作子行文案；**不**发明第二套 phase 名（可用现有 done 行）

- [ ] **Step 4: 确认无 Worker 卡片组件**

- [ ] **Step 5: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(ui): OperationStack harness plan/worker hierarchy

EOF
)"
```

---

### Task 4: TaskBoard 一级 H1 + 条件二级

**Files:**
- Modify: `TaskBoardPanel.vue`
- Modify: steps → board 投影（若 harness 用 `phase=tasks` 或 metadata 挂 H1；**若后端暂无 tasks 步**，前端可从 `plan` step metadata / 并行 Redis 暂不拉——优先消费 SSE `tasks` 或 plan metadata.taskQueue）

**探查后选型（实现者写进 report）：**
- A) 后端已有 `tasks` 步投影 H1 → 只改 Panel 一/二级嵌套  
- B) 仅有 plan/worker 步 → 最小：从 plan step `metadata` 读 taskQueue 合成 board props（**禁止**再调 LLM）；缺则本 task 只做 Panel API，标记 DONE_WITH_CONCERNS 等 H-7/后端补 SSE

**展示规则：**
- 一级：调度单元 checklist；可按 `dependsOn` **波次分组并排**（无边）
- 二级：仅当 worker todolist items 非空才渲染；结束不收束进 handoff

- [ ] **Step 1: 读现 `TaskBoardPanel` props/items 结构**

- [ ] **Step 2: 扩展嵌套 + 波次样式**

- [ ] **Step 3: 接到 OperationStack / ChatView 已有 TaskBoard 挂载点**

- [ ] **Step 4: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(ui): TaskBoard primary waves and optional secondary todos

EOF
)"
```

---

### Task 5: 断开 PlanApproval UI（保留 Confirm 壳）

**Files:**
- Modify: `PlanWorkflowPanel.vue` — 不再渲染 `PlanApprovalActions`（或 `v-if="false"` + 注释阶段 D 删除）
- Grep: `PlanApprovalActions` 引用清主路径
- **保留** `CollapsibleConfirmPanel` 给 HITL/Recovery/Decision

- [x] **Step 1: Grep 引用并断链**

- [x] **Step 2: 静态 workflow DAG 回归：有图消息仍显示画布**

- [x] **Step 3: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(ui): disconnect PlanApproval actions from plan panel

EOF
)"
```

---

### Task 6: contentInterleave / 历史消息兼容

**Files:**
- Modify: `contentInterleave.ts` — harness 消息走 ReAct 式穿插，勿当旧 plan-workflow 锚定 answer
- Test: 扩展现有 interleave 测

- [ ] **Step 1: `isPlanWorkflowSteps` 与 harness 互斥**（有 worker 无 graph → 非 plan-workflow 穿插）

- [ ] **Step 2: Commit**

```bash
git commit -m "$(cat <<'EOF'
fix(ui): interleave harness timeline separately from plan-workflow

EOF
)"
```

---

### Task 7: 文档状态

**Files:**
- `docs/superpowers/specs/archive/2026-08-05-planner-executor-rebuild-design.md` §7.0 H-6 → 本 plan / 完成后 ✅  
- `docs/superpowers/plans/2026-08-13-planner-h6-frontend.md` 状态  
- `CLAUDE.md` / `implementation-plan.md` / `specs/README.md` 进度行  
- 注明：Composer task 三模式 + 分支下移 + 去 AI 提示随 H-6 交付；TaskBoard H1 待 `tasks` SSE

- [x] **Commit** `docs: mark H-6 frontend plan complete`

---

## Out of scope

| 项 | 去向 |
|----|------|
| H-7 Live P1–P8 | 下一 plan |
| 删 PlanWorkflow/Approval 后端与组件文件 | 阶段 D |
| 工作区抽屉顶栏另一处分支气泡 | 可保留；本 plan 只保证**输入框下**有主分支选择 |
| 模型选择器挪位 | 不改（仍在 toolbar 右） |

---

## Spec coverage（self-review）

| 要求 | Task |
|------|------|
| rebuild §4 分层时间线 | T2 T3 |
| TaskBoard 一/二级 | T4 |
| 去 Approval UI / 保留 Confirm | T5 |
| 静态 DAG 保留 | T2 T5 |
| kind=task 三模式 | T1 |
| 分支在输入框下 | T1 |
| 去掉 AI 生成提示 | T1 |
| kind ⊥ executionMode | T1 |

---

## 风险与假设

1. 后端 harness 若暂无 `tasks` SSE，一级看板可能空 → T4 选 B 并记 follow-up，**不**阻塞时间线。  
2. 历史 `plan-workflow` 消息仍可能带 `planGraph` → 继续 DAG，避免「旧会话空白」。  
3. 抽屉头部分支控件与 subbar 并存可接受；用户主操作面是输入框下分支。
