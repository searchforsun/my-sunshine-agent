# 4.7.9 ReAct Request Decision（主 Agent 主动向用户出选择题 / 需求澄清）

> **状态**：✅ Chat ReAct MAIN 已实现 · **已归档**（2026-08-12 tech-debt-refactor）· ⬜ Planner MAIN / D12 见 [../2026-08-12-react-request-decision-planner-d12.md](2026-08-12-react-request-decision-planner-d12.md)  
> **契约 SSOT**：[2026-08-11-request-decision-cursor-align-design.md](./2026-08-11-request-decision-cursor-align-design.md)（替换下文 §3–§6 旧 `choice`/`customInput` 叙述；以代码与 cursor-align 为准）  
> **日期**：2026-07-28 · **修订**：2026-08-11（Chat MAIN + Cursor align）· 2026-08-12（归档）  
> **编号**：阶段四 **4.7.9**（原 4.7.7 占号冲突，goal-alignment 已用 4.7.7）  
> **相关**：`AgentRuntime` / `HitlTokenRegistry`（阻塞唤醒）· [spawn-subagent](./2026-07-18-react-spawn-subagent-design.md) · [taskboard](./2026-06-24-react-taskboard-design.md) · [goal-alignment](./2026-07-27-react-goal-alignment-design.md) · [planner-executor-rebuild](./2026-08-05-planner-executor-rebuild-design.md)  
> **灰度**：Nacos `agent.execution.react.decision.enabled` 默认 **false**（D21）；Live 见 `scripts/verify_decision_live.py` 前置说明

---

## 1. 背景与目标

ReAct Agent 在长链路推理中常遇到**需求歧义**或**多方案抉择**：用户提问存在多种合理解读，或执行路径有多条可行方案。当前 Agent 只能「猜」一个方向继续，一旦猜错整个链路白跑。

类似 Cursor：Agent 发现「不确定你要哪种」时**主动暂停**出选择题，用户选择后继续。与「工具调用确认（HITL confirm_cancel）」是**两条平行路径**：

| 路径 | 触发方 | 触发时机 | 用途 |
|------|--------|----------|------|
| 工具确认 HITL（现有） | 平台自动拦截 | 写工具执行前 | 安全闸：要不要执行这个工具 |
| **决策选择（本设计）** | **Agent 主动调用元工具** | 推理中遇到歧义/多方案 | 需求澄清：我理解有这几个方案，你选哪个 |

| 目标 | 说明 |
|------|------|
| 需求澄清 | Agent 遇歧义主动暂停，出 ≥2 项选择题，等待用户决策后继续 |
| 决策回传 | 用户选择作为 tool result 回传（固定短格式，非二次加工） |
| 输入支持 | ~~选项 requireInput / allowCustomInput~~ → 见修订：仅 `id`+`label`；平台每题必有「其他」手写 |
| 样式 | 复用三兄弟选项行（对号 18px + 透明底 + 边框分区 + 内描边选中） |
| 阻塞 | 复用 `HitlTokenRegistry` 的 `CompletableFuture + Redis token` 模式 |
| 暂停/续跑 | 保留决策快照；续跑 re-await **同一题**，不重新出题 |

### 1.1 与既有能力正交

| 能力 | 关系 |
|------|------|
| 工具确认 HITL | 安全闸；本能力是 Agent 主动澄清，**不混用** |
| `spawn_subagent` | 隔离子 Agent；本能力不产生子 Agent |
| TaskBoard（`todo_write`） | 软规划清单；本能力是一次性决策 |
| Plan Approval（4.14 废弃） | 历史能力；本能力是 ReAct/Planner 推理中澄清，**不依赖** PlanApproval UI |
| Workflow 节点 Recovery | 失败恢复；本能力是主动澄清 |
| 4.7.7 GoalAlignment / FailureBudget | 软提醒；本能力是硬阻塞。失败预算**不计** / **不排除错** `request_decision` |

---

## 2. 方案选型

采用 **元工具 `request_decision`**（`ToolkitScope.MAIN` 注册），内部复用 HITL 阻塞唤醒等待用户决策。

不采用：扩展写工具 HITL 为选择题；临时迷你 Plan；前端轮询。

---

## 3. 架构与调用契约

```
MAIN ReAct / Planner MAIN
  └─ tool_call: request_decision({ question, options[], allowCustomInput? })
        └─ DecisionRegistry.register → 下发 phase=decision（lifecycle=awaiting）+ 阻塞
              · 用户选择 → POST /api/generations/{id}/decisions/{token}/resolve
              · DecisionRegistry.resolve 唤醒
        └─ tool result（固定短格式）→ Agent 继续
```

| 项 | 约定 |
|----|------|
| 工具名 | `request_decision` |
| Catalog | orchestrator **内置元工具**（同 `spawn_subagent`），**不**进 tool-service |
| 入参 | **已弃用扁平 question/options** → 见修订：`title?` + `questions[{id,prompt,options[{id,label}],allowMultiple?}]`；选项无 description |
| 注册范围 | 见 **D14**：Chat ReAct MAIN + Planner MAIN；Worker / SUB / Expert **不注册** |
| 并发 | **D15**：同一 `messageId` 同时最多 **1** 个 awaiting decision；第二个调用直接错误 JSON |
| 回传 | 固定短格式文本（§5.1），不对结果二次加工/截断展示 |
| 阻塞 | `CompletableFuture<DecisionResult>` + Redis `sunshine:decision:pending:` |
| 失败 | 超时 → tool result 含超时信息；Catalog 约束禁止同参立刻再调 |

### 3.1 数据结构

```java
// ⚠️ 契约已被 Cursor 对齐修订替换，见 archive/2026-08-11-request-decision-cursor-align-design.md
// 选项仅 id + label（label 即答案文案，无 description / requireInput，避免模型歧义）
public record DecisionOption(String id, String label) {}
```

### 3.2 组件

| 组件 | 职责 |
|------|------|
| `RequestDecisionTool` | 元工具：校验、注册、阻塞、返回短格式 result |
| `DecisionRegistry` | Future + Redis；`register` / `awaitDecision` / `resolve` / `cancelWaitersForMessage`（骨架对齐 HITL，**独立类**，不改造 HITL 热路径） |
| `DecisionTimelineSupport` | 下发 `phase=decision` 卡片 |
| `DecisionResumeSupport` | ReAct/Planner 续跑 re-await（对齐 `findReactAwaitingHitlStep` 模式，**不**进 `WorkflowNodeRunner`） |
| `DynamicToolkitFactory` | MAIN（含 Planner）注册；SUB 剥离；白名单拒绝当 Catalog 工具 |
| `ProcessingStepMiddleware` | 跳过 `tool-*` 步；`partitionByReadWrite` 视为只读；`recordToolCompleted` |
| 前端 `DecisionCard` | 主时间线卡片（**自建容器**，见 D16） |
| `OperationStack` | `phase=decision` → `DecisionCard` |

---

## 4. Timeline / UI

### 4.1 主时间线

- 每次成功发起 → **一张**卡片：`id=decision-{token}`，`phase=decision`。
- **lifecycle（D17，与 HITL 对齐）**：
  - 等待用户：`awaiting`
  - 已提交：`done`
  - 超时 / 用户停止：`paused`
  - 工具侧异常：`error`
- 展示：Catalog `timeline.steps.decision` 摘要 + `metadata.decision`（question / options / allowCustomInput / token / expiresAt）。
- **禁止**前端硬编码问题/选项；**禁止**对模型生成的 question/options **截断兜底**（入参超长在工具校验阶段拒收，见 §5.1）。

### 4.2 DecisionCard UI（D16）

**不依赖**即将随 4.14 删除的 `CollapsibleConfirmPanel` / `PlanApprovalActions`。  
`DecisionCard.vue` **自建**折叠容器（结构可参考 ConfirmPanel，但独立组件，供 HITL 未来若抽公共壳也不反向依赖 PlanApproval）。

选项列表复用 `ExecutionModeSelector` 的 `.mode-menu-item` 视觉约定：

| 元素 | 规则 |
|------|------|
| 选项行 | flex + gap 10px + padding 8×10；`background: transparent`；`1px solid var(--sun-border)` |
| hover | `var(--sun-row-hover)`；仅改边框/底，无灰面板 |
| 选中 | `border-color: var(--sun-accent)` 内描边，无灰底填充 |
| 对号 | `CheckmarkOutline` 18px |
| 标题/描述 | `--sun-font-base`；描述 **最多 3 行换行**（勿 `nowrap` 视觉裁切） |
| 附加输入 | `--sun-black` + `sun-field` |
| 提交 | `hitl-btn hitl-btn-primary`（28px） |

### 4.3 交互流程

1. Agent 调用 → SSE `phase=decision`，`lifecycle=awaiting`，`metadata.decision` 完整载荷  
2. 用户点选 → `is-selected`；`requireInput` 或自定义项展开 textarea  
3. 「提交决策」→ `POST .../decisions/{token}/resolve`；校验 choice / customInput  
4. `resolve` 唤醒 → tool result → 卡片 `lifecycle=done`

### 4.4 SSE

- 仅 `type:step`（`metaStep`），**不新增** SSE type。  
- `metadata.decision` 示例：

```json
{
  "token": "{uuid}",
  "question": "您希望按哪种方式处理？",
  "options": [
    {"value": "plan_a", "label": "方案A：快速处理", "description": "...", "requireInput": false},
    {"value": "plan_b", "label": "方案B：完整流程", "description": "...", "requireInput": true}
  ],
  "allowCustomInput": false,
  "expiresAt": 1753721880000
}
```

---

## 5. 后端实现

### 5.1 元工具 `RequestDecisionTool`

仿 `SpawnSubagentTool`，orchestrator 内置：

**校验（失败均返回错误 JSON，不下发决策卡片）**：
- `react.decision.enabled == false`
- `question` 空白或超长（建议 ≤ 500 字）
- `options == null || size < 2`；单项 `value`/`label` 空白；`value` 重复；单 option 文本过长（建议 label≤64、description≤256）
- 非 MAIN / `sub-` bridge → 硬拒
- 同 `messageId` 已有 awaiting decision → 硬拒（D15）

**阻塞流程**：
1. `DecisionRegistry.register` → token  
2. `DecisionTimelineSupport.begin`（`lifecycle=awaiting`）  
3. `awaitDecision(token, timeoutSec)`  
4. 成功 → `complete` + 短格式 result；超时/取消 → `pause` + 超时/取消 result  

**tool result 短格式（D18，固定可解析，非散文二次加工）**：

```text
choice={value}
label={label}
customInput={text or empty}
```

超时：

```text
choice=__timeout__
timeoutSec={n}
```

取消/暂停中断：

```text
choice=__cancelled__
```

### 5.2 `DecisionRegistry`

- Redis 前缀：`sunshine:decision:pending:`（区别 HITL）  
- 超时 / 中断 / 异常三路回调骨架对齐 `HitlConfirmationService.waitForDecision`  
- `resolve(token, choice, customInput)`：校验 token 存在、未过期；`choice` ∈ options ∪ `{__custom__}`；若选项 `requireInput` 或 `__custom__` 则 `customInput` 非空白，否则 **不 complete Future**，API 返回 400  
- `cancelWaitersForMessage(messageId)`：用户停止时中断  

### 5.3 `DecisionTimelineSupport`

| 方法 | lifecycle |
|------|-----------|
| `begin(...)` | `awaiting` |
| `complete(..., result)` | `done` |
| `fail(..., errorMsg)` | `error` |
| `pause(...)` | `paused` |

### 5.4 注册到工具集

`DynamicToolkitFactory`：
- 白名单显式跳过 `request_decision`（勿当 Catalog 工具）  
- `ToolkitScope.MAIN` 且 `react.decision.enabled` → 注册（**含 Planner MAIN**，D14）  
- Worker / SUB：不注册  

### 5.5 Middleware

`ProcessingStepMiddleware`（语义定位，勿钉死行号）：
- `onActing`：`request_decision` **不上** `tool-*` 步，但 `recordToolCompleted`  
- `partitionByReadWrite`：视为只读（不走写工具串行 HITL 闸）  
- 与 4.7.7：FailureBudget **不计**本工具；GoalAlignment 触发条件中的「业务 tool」**不含**本工具  

### 5.6 API 与 BFF

**为何走 generations 而非 `/chat/confirm-tool`（D19）**：HITL 确认的是「写工具是否执行」，绑定 toolId/params；decision 绑定 generation + token + 选择题载荷，与 `cancelSubagent` / `cancelTool` 同属 generation 交互面，避免把选择题塞进 confirm-tool 语义。

| 层 | 改动 |
|----|------|
| orchestrator | `POST /api/generations/{id}/decisions/{token}/resolve` body `{choice, customInput?}` |
| BFF | `OrchestratorClient.resolveDecision` + 透传（清单必含，对齐 cancelSubagent） |
| 前端 | `sunshine-ui/src/api/decisions.ts` |

```java
@PostMapping("/generations/{id}/decisions/{token}/resolve")
public Mono<Map<String, Object>> resolveDecision(...) { ... }

public record ResolveDecisionRequest(String choice, String customInput) {}
```

校验失败 → 400 + 明确错误码（`decision_invalid_choice` / `decision_input_required` / `decision_expired`）；成功 → `{accepted: true}`。

### 5.7 暂停 / 续跑（D20 — 纠正旧稿）

**禁止**改 `WorkflowNodeRunner`；**不存在** `ReActResumeService` 类名。

对齐 ReAct 写工具 HITL 续跑模式：

| 步骤 | 做法 |
|------|------|
| 暂停落库 | 决策步已在 stepsBuffer（`phase=decision` + `metadata.decision`）；`ProcessingStepLifecycleOps` 新增 `findReactAwaitingDecisionStep`（扫非 `node-*`、`phase=decision`、lifecycle=`awaiting`/`paused` 且仍待决） |
| Checkpoint | 若需显式 pending：扩展 `PendingInteraction` 增加 `kind=decision` + decision 字段，并改 `findPendingInteraction` **同时扫描** `decision-*` 步（勿仅 `node-*`）；`PlanJsonCodec` 同步序列化 |
| 续跑 | 新建 `DecisionResumeSupport`：检测 awaiting decision → **重新 register token**（或恢复 Redis 未过期 token）→ 更新 step metadata 中的 token/expiresAt → **不重新出题** → 再 `awaitDecision` |
| 预决策 | `consumeDecisionPreApproval(messageId, token|fingerprint)`：续跑前用户已 resolve 则跳过二次阻塞（模式对齐 `consumeHitlPreApproval`） |
| 上下文 | `ReactResumeContextSupport` 可增补「待决策摘要」注入块（question + options 原文，不截断） |

`WorkflowNodeRunner` **仅**继续处理 workflow `hitl` / `recovery`，不出现 `kind=decision` 分支。

---

## 6. 前端

### 6.1 `DecisionCard.vue`

路径：`sunshine-ui/src/components/operation/DecisionCard.vue`  
自建容器 + §4.2 选项样式；props：`step` / `live`。

- 读 `step.metadata.decision`  
- `lifecycle === 'awaiting' && live` 可交互  
- `submit` → `resolveDecision`；400 展示后端错误，不本地兜底改写 options  

### 6.2 `OperationStack`

在 SubagentCard 分支旁：

```vue
<DecisionCard
  v-else-if="step.phase === 'decision'"
  :step="step"
  :live="live && lifecycleOf(step) === 'awaiting'"
/>
```

### 6.3 API / 类型

- `api/decisions.ts`：`resolveDecision(messageId, token, choice, customInput?)`  
- `processingSteps.ts`：`phase=decision` + `metadata.decision` 辅助解析  
- SSE：复用现有 `type:step`，**不新增** handler  

---

## 7. 配置（Nacos，非提示词）

```yaml
agent:
  execution:
    react:
      decision:
        enabled: false          # D21：默认关，Live/灰度再开
        timeout-sec: 300
```

`AgentExecutionProperties.React.Decision`：`enabled` / `timeoutSec`。  
改 YAML → `python scripts/sync_nacos.py` → 重启 orchestrator。

---

## 8. Prompt Catalog

SSOT：`docker/mysql/init/19-sunshine-resource.sql`（**不是**已不存在的 `17-sunshine-prompt-manager.sql`）。

### 8.1 `mode-overlay.react` 追加

```
- 【RequestDecision·使用场景】需求歧义或需在多方案间抉择时，调用 request_decision。
- 【RequestDecision·禁止场景】勿用于工具调用确认；用户意图已明确时勿出题；勿滥用。
- 【RequestDecision·选项】≥2；value 英文蛇形互异；label 中文；description 说明取舍；requireInput 仅必要时 true。
- 【RequestDecision·超时】若 tool result 为 choice=__timeout__：基于已有信息收束或换不依赖用户选择的路径；禁止立刻以相同 question/options 再调 request_decision。
- 【RequestDecision·续跑】用户 customInput 视为最终决策，勿再追问同一题。
- 【RequestDecision·与 TaskBoard】清单用 todo_write；澄清/抉择用 request_decision。
```

Planner 若共用 MAIN toolkit：在 `mode-overlay` / planner overlay 同步同等约束（或复用 react overlay 已叠加层）。

### 8.2 `timeline.steps.decision`

```json
{"before":"正在等待用户决策","active":"等待决策：{question}","after":"用户已选择：{choice}"}
```

---

## 9. 边界与非目标

**做**：MAIN（Chat ReAct + Planner）元工具 + 阻塞 + 决策卡片 + 暂停/续跑；自建 UI 容器 + 三兄弟选项样式；`requireInput` / `allowCustomInput`。

**不做**：
- Worker / SUB / Expert 调用  
- 静态 Workflow 节点内调用（本版）  
- 决策多轮 history / rounds[]  
- 前端硬编码模板；对 question/options 截断兜底  
- 混用写工具 HITL；进 tool-service Catalog  
- 依赖 `CollapsibleConfirmPanel` / PlanApproval  

**锁定决策**：见 §14。

---

## 10. 实施切片

| 切片 | 内容 | 验收 |
|------|------|------|
| **P0** | 元工具 + Registry + resolve API + BFF + Middleware 跳过 + Catalog/Nacos（enabled 默认 false） | D8/D9/校验/单测 |
| **P1** | DecisionCard + OperationStack + 样式 | D1–D4/D10 |
| **P2** | 暂停/续跑（`findReactAwaitingDecisionStep` + `DecisionResumeSupport`）+ Live | D5–D7；`verify_decision_live.py` |

---

## 11. 检查门

| # | 场景 | 期望 |
|---|------|------|
| D1 | 调用（2 选项） | 一张 `awaiting` 决策卡片；Agent 阻塞 |
| D2 | 用户选择 | `done`；tool result 短格式含 choice/label；继续推理 |
| D3 | `requireInput` | textarea；空提交 400；有内容才唤醒 |
| D4 | `allowCustomInput` | 末尾「自定义」；`choice=__custom__` |
| D5 | 用户停止 | `paused`；续跑可恢复同一题 |
| D6 | 续跑 | re-await 不重新出题；已决策则跳过阻塞 |
| D7 | 超时 | `paused` + `choice=__timeout__`；模型不立刻同参重调（Prompt） |
| D8 | SUB 调用 | 硬拒；无卡片 |
| D9 | options&lt;2 / value 重复 | 硬拒；无卡片 |
| D10 | 样式 | 18px 对号、透明底、边框分区、内描边选中 |
| D11 | 同消息第二次 decision | 错误 JSON；不出现第二张 awaiting 卡 |
| D12 | Planner MAIN | 可调用（与 Chat MAIN 同）；Worker 不可 |

Live：`scripts/verify_decision_live.py`。

---

## 12. 文档同步（实施时）

- [x] `phase4-platformization-design.md` §4.7 含 4.7.9  
- [x] `implementation-plan.md` 阶段四 4.7 行（**4.7.9 Request Decision ✅**）  
- [x] `CLAUDE.md` 时间线表补 `decision-*`；扩展表补 `request_decision`；进度行 / Live 脚本表  
- [x] 4.14 rebuild：注明 ConfirmPanel 删除**不**阻碍 DecisionCard（D16 自建）  
- [x] Live：`scripts/verify_decision_live.py`（D1–D4/D11 hard；D5–D7 opt；D8/D9 soft；**不做 D12**）  
- 归档：Planner MAIN（D12）落地后再移入 `archive/`（本切片仅 Chat MAIN）

---

## 13. 风险与对策

| 风险 | 对策 |
|------|------|
| 滥调打断 | overlay 约束；`enabled` 默认 false；D15 同时仅 1 个 |
| 与 HITL 视觉混淆 | 独立 `phase=decision` 卡片（非内联按钮组） |
| 续跑 token 失效 | 快照保留 question/options；续跑重发 token |
| 4.14 删 ConfirmPanel | D16 自建容器，零依赖 PlanApproval |
| 描述过长撑破 UI | 入参长度校验 + CSS 最多 3 行，**不做内容改写** |

---

## 14. 决策记录

| ID | 决策 |
|----|------|
| D14 | 注册范围：Chat ReAct MAIN + **Planner MAIN**；Worker/SUB/Expert 否 |
| D15 | 同一 message 同时最多 1 个 awaiting decision |
| D16 | UI **自建** DecisionCard 容器；不依赖 `CollapsibleConfirmPanel`（4.14 可删 PlanApproval 壳） |
| D17 | 等待用户 lifecycle = **`awaiting`**（与 HITL 对齐） |
| D18 | tool result 用固定短格式 `choice=/label=/customInput=` |
| D19 | resolve API 挂在 `/api/generations/.../decisions/...`；BFF 必透传 |
| D20 | 续跑走 `findReactAwaitingDecisionStep` + `DecisionResumeSupport`；**禁止** `WorkflowNodeRunner` 分支 |
| D21 | `enabled` 默认 **false** |
| D22 | Prompt/SQL SSOT = `19-sunshine-resource.sql`；服务名 tool-service；任务板工具名 `todo_write` |

---

## 15. 改动文件清单

| 层 | 文件 | 类型 |
|----|------|------|
| 元工具 | `orchestrator/.../agent/RequestDecisionTool.java` | 新建 |
| 阻塞 | `orchestrator/.../agent/DecisionRegistry.java` | 新建 |
| 时间线 | `orchestrator/.../agent/DecisionTimelineSupport.java` | 新建 |
| 续跑 | `orchestrator/.../agent/DecisionResumeSupport.java` | 新建 |
| 标签 | `orchestrator/.../processing/DecisionLabels.java` | 新建 |
| DTO | `DecisionOption` / `DecisionResult` / `ResolveDecisionRequest` | 新建 |
| 注册 | `DynamicToolkitFactory.java` | 修改 |
| 中间件 | `ProcessingStepMiddleware.java` | 修改 |
| 生命周期 | `ProcessingStepLifecycleOps.java`（`findReactAwaitingDecisionStep`） | 修改 |
| Pending | `PendingInteraction.java` + `PlanJsonCodec` + `findPendingInteraction`（若采用显式 kind） | 修改 |
| 续跑上下文 | `ReactResumeContextSupport.java` | 修改 |
| Controller | `GenerationController.java` | 修改 |
| 配置 | `AgentExecutionProperties.java` + `sunshine-orchestrator.yaml` | 修改 |
| Prompt | `docker/mysql/init/19-sunshine-resource.sql` | 修改 |
| BFF | `OrchestratorClient` + 透传 Controller/路由 | 修改 |
| 前端 | `DecisionCard.vue` / `OperationStack.vue` / `decisions.ts` / `processingSteps.ts` | 新建/修改 |
| 运维 | `scripts/verify_decision_live.py` | 新建 |

**明确不改**：`WorkflowNodeRunner`（无 decision 分支）。

---

## 16. 自检清单

- [x] 续跑落点与现网 HITL ReAct 模式一致  
- [x] 与 4.14 ConfirmPanel 删除无冲突  
- [x] Planner / Worker 注册范围已写清  
- [x] SQL/服务名/`todo_write`/lifecycle 已纠正  
- [x] 校验、超时 Prompt、BFF、单 decision 并发已补  
- [x] 不混用写工具 HITL；不新增 SSE type；不进 tool-service Catalog  
- [x] 不对模型 question/options 做截断兜底  
