# Plan-Workflow 暂停/续跑一致性方案

> **阶段三收尾 · 3.9.5**（Plan-Workflow 生产加固最后一环）  
> 状态：已评审，待实施  
> 日期：2026-06-26  
> 关联：`GenerationJob.cancel`、`ExecutionPlanStore.markPaused`、`findResumableForMessage`、`PlanWorkflowPanel`、`ChatView.canResume`  
> 前置：3.9 节点重试/降级/HITL/Recovery **基础能力 ✅**（本会话）；本 spec 修 **pause/resume 语义与 UI 一致性**

## 1. 背景

当前「继续生成」在 UI 上统一入口，但底层存在 **检查点续跑** 与 **软续跑（按 intent 重跑）** 两套语义。部分分支暂停后 UI 与 DB 不一致，或在 HITL/Recovery 阻塞态停止后续跑丢失交互。本方案只修 **不一致/误导** 项，ReAct 逐步 checkpoint 留阶段四。

## 2. 目标与非目标

### 目标

| ID | 目标 |
|----|------|
| G1 | 用户暂停 Plan/静态 Workflow 后，续跑 **从同一 DAG 节点** 继续（已有能力加固） |
| G2 | Planner 规划阶段停止后，续跑行为与 UI 文案 **可预期**（有 validated plan 则继续执行，否则重新规划） |
| G3 | HITL/Recovery 等待中停止后，续跑 **恢复同一交互**（确认框/三按钮），而非 silent 重跑工具 |
| G4 | 无检查点时按钮文案为「重新生成」，有检查点时为「继续执行」 |
| G5 | ReAct 停止后 think/tool 步骤不再长期显示 running |
| G6 | wfCtx 为空时拒绝 Plan 检查点续跑并给出明确错误 |

### 非目标

- ReAct Agent 逐步 checkpoint / TaskBoard（阶段四）
- 已完成/已终止消息可续跑
- 修改 `resumeCount` 上限默认值

## 3. 现状问题 → 方案映射

| 问题 | 优先级 | 方案章节 |
|------|--------|----------|
| Planner 阶段 stop 无 DB checkpoint | P0 | §4 |
| HITL/Recovery 阻塞 stop 后续跑丢交互 | P0 | §5 |
| 无 checkpoint 仍显示「继续生成」 | P0 | §6 |
| ReAct think/tool 停止后仍 running | P1 | §7 |
| wfCtx 空仍尝试 Plan 续跑 | P1 | §8 |

---

## 4. P0-A：Planner 阶段暂停与续跑

### 4.1 根因

`GenerationJob.persistWorkflowPauseIfNeeded()` 仅当 `execution_plan.status == running` 时落库；Planner 在 `planAndExecute` → `workflowPlanner.plan()` 期间 plan 多为 `draft`，`markRunning` 在 `runValidatedPlan` 才调用。

### 4.2 检查点模型扩展

扩展 `WorkflowCheckpoint` JSON（向后兼容）：

```json
{
  "resumeNodeId": "list_oa_tasks",
  "wfCtxJson": "{...}",
  "pausePhase": "PLANNING | EXECUTING",
  "validatedPlanJson": "<optional, PLANNING 且已 validate 时冗余快照>"
}
```

| pausePhase | 含义 | resumeNodeId |
|------------|------|--------------|
| `EXECUTING` | DAG 节点执行中暂停（现有） | 当前/下一待执行节点 |
| `PLANNING` | Planner/校验/Replan 阶段暂停 | 空串或首个业务节点 id（若 validated 已有） |

### 4.3 后端改动

**`GenerationJob.persistWorkflowPauseIfNeeded`**

- 允许落库条件扩展为：`status ∈ { running, validated }` 或 `status == draft && validatedJson 非空`（按 `ExecutionPlanStore` 现有字段选最稳条件）。
- 推断 `pausePhase`：
  - 有 `currentNodeId` 或 `findLastRunningWorkflowNodeId` → `EXECUTING`
  - 否则若 `validated_json` 已有 → `PLANNING`，`resumeNodeId = def.linearOrder()[0]`（首个业务节点）
  - 否则 → `PLANNING`，`resumeNodeId = ""`
- `wfCtxJson`：Planner 阶段可为 `{}` 或仅含 `start` 节点输出（从 `WorkflowPauseService` 或 DB 快照读取）。

**`ExecutionPlanStore.markPaused`**

- 接受 `validated` 态写入（当前仅 `running|paused`）。
- 新增 `markPausedPlanning(planId, checkpoint)` 或在原方法放宽 status 校验。

**`PlanWorkflowExecutor.resumePaused`**

```
load checkpoint
if pausePhase == PLANNING:
  if validated_json 存在:
    markRunning → runValidatedPlan(enriched)   // 跳过 Planner，直接执行 DAG
  else:
    markRunning → planAndExecute(planId, 1, null)  // 重新规划
else:
  现有 resumeDynamicDefinition 路径
```

**`WorkflowExecutor.resumeDynamicDefinition`**

- 若 `resumeNodeId` 为空且 `pausePhase==PLANNING`：不调用；由上层 `PlanWorkflowExecutor` 分支处理。

### 4.4 前端改动

**`pauseRunningWorkflowNodes`（前后端对称）**

- 当仅有 `plan` 步 running、无 `node-*` running 时：**不要将 plan 步标为 paused**（保持 running 或改为与消息一致的 interrupted 语义）。
- 或 plan 步单独标 `lifecycle: paused` 但 **DAG 不出现误导性黄节点**（plan 步不在 DAG 上，可接受）。

**`PlanWorkflowPanel` / `PlanDagGraph`**

- Planner 阶段停止：DAG 若尚未物化，不展示「节点 paused」；仅消息级 interrupted。

### 4.5 验收

1. 发送 Plan 请求 → Planner 调用 LLM 中点停止 → 继续执行 → **不重新调用 Planner**（若 validated 已写入）。
2. 刚发请求、Planner 尚未产出 → 停止 → 继续 → **重新规划**（无 validated）。
3. validated 已写入、DAG 已展示、首个 node 未跑 → 停止 → 继续 → **从首个 node 执行**。

---

## 5. P0-B：HITL / Recovery 阻塞态停止与续跑

### 5.1 根因

`HitlConfirmationService` / `WorkflowNodeRecoveryService` 在 `Future.get()` 阻塞；`GenerationJob.cancel()` dispose 执行流但未持久化 **待交互态**；续跑走 `resumeDynamicDefinition` **重跑节点**，HITL/Recovery UI 丢失。

### 5.2 设计原则

- **停止 ≠ 终止**：用户点停止表示「稍后继续」，不应清掉 awaiting metadata。
- **续跑 ≠ 重跑工具**：若检查点标记 `pendingInteraction`，续跑应 **重新进入等待**（新 token 可接受，但 error/params 上下文须一致）。

### 5.3 检查点扩展

```json
{
  "resumeNodeId": "approve_oa",
  "wfCtxJson": "{...}",
  "pausePhase": "EXECUTING",
  "pendingInteraction": {
    "kind": "hitl | recovery",
    "nodeId": "approve_oa",
    "errorMessage": "...",
    "hitlToolId": "approve_oa_task",
    "hitlParamsSummary": "...",
    "recoveryAttempts": [ ... optional ... ]
  }
}
```

### 5.4 停止路径（`GenerationJob.cancel`）

1. 扫描 `stepsBuffer` 中带 `metadata.hitl.status=awaiting` 或 `metadata.recovery.status=awaiting` 的 `node-*` 步。
2. 若存在：
   - 写入 `pendingInteraction` 到 checkpoint。
   - **不对该步调用 `toPaused`**：保持 `lifecycle=error`（recovery）或 `running`+hitl metadata（HITL）。
   - 其他 running 的 `node-*` 仍可按现有逻辑 paused（若不在交互节点上）。
3. `markPaused` 照常，`pausePhase=EXECUTING`。

### 5.5 续跑路径

**`PlanWorkflowExecutor.resumePaused` 分支：**

```
if checkpoint.pendingInteraction != null:
  ① 恢复 wfCtx + 重放 steps 快照（MySQL steps_json 已有 metadata）
  ② 下发 PROGRESS step（保留/重建 awaiting metadata）
  ③ 重新 register Redis token + Future
  ④ awaitRecovery / awaitConfirmation（同首次）
  ⑤ 用户决策后 → 现有 RETRY/SKIP/approve 分支
else:
  resumeDynamicDefinition（现有）
```

**实现要点：**

| 组件 | 改动 |
|------|------|
| `WorkflowNodeRecoveryService` | 新增 `resumeAwaiting(checkpoint, session, ...)`：不 fail 节点，直接 attach awaiting + 阻塞 |
| `HitlConfirmationService` | 新增 `resumeAwaitingFromCheckpoint(...)` |
| `WorkflowExecutor.executeNode` | 若 `streamCtx.resumeInteraction()` 非空且 nodeId 匹配 → 跳过 `runNode`，走 interaction resume |
| `ExecutionStreamContext` | 可选字段 `ResumeInteractionHint` |

### 5.6 前端

- 停止后：节点仍显示 **待确认**（紫）或 **发生错误+三按钮**（红呼吸），**不要**变成黄「暂停」覆盖 awaiting。
- `mapStepStatus`：`isRecoveryAwaiting` / `stepHasHitlAwaiting` **优先于** `lifecycle===paused`。
- 续跑后 SSE 再次下发 awaiting metadata → 抽屉自动打开（现有 `maybeAutoOpenDrawer`）。

### 5.7 验收

1. tool 节点 HITL 弹出 → 停止 → 继续执行 → **仍显示同一工具确认**（可新 token），不先调 tool-manager。
2. 节点失败 recovery 三按钮 → 停止 → 继续 → **仍显示三按钮**，执行记录保留。
3. 停止后点「终止」→ 不可续跑（现有行为）。

---

## 6. P0-C：续跑按钮分态（继续执行 vs 重新生成）

### 6.1 判定规则

| 条件 | 模式 | 按钮文案 | 后端路径 |
|------|------|----------|----------|
| `findResumableForMessage` 且 `pausePhase=EXECUTING` 或有 `pendingInteraction` | `checkpoint` | **继续执行** | `resumePaused` |
| `findResumableForMessage` 且 `pausePhase=PLANNING` | `planning` | **继续执行计划** | §4 resume 分支 |
| 无 resumable plan，消息 `interrupted/failed` | `regenerate` | **重新生成** | `resolveChunkFlux` 软续跑 |
| `intent === 'knowledge'` | — | 不显示 | — |

### 6.2 前端

**`sunshine-ui/src/api/resumeMode.ts`（新）**

```ts
export type ResumeMode = 'checkpoint' | 'planning' | 'regenerate'

export function resolveResumeMode(msg: ChatMessage): ResumeMode {
  const hasPausedNode = msg.steps?.some(s =>
    s.id.startsWith('node-') && s.lifecycle === 'paused')
  const hasPlanStep = msg.steps?.some(s => s.phase === 'plan' || s.id === 'plan')
  // 可选：从 message.metadata / intent JSON 读 planId 调 GET execution-plans/:id 确认 PAUSED
  if (hasPausedNode || hasAwaitingInteraction(msg.steps)) return 'checkpoint'
  if (hasPlanStep && msg.status === 'interrupted') return 'planning' // 保守
  return 'regenerate'
}
```

**`ChatView.vue`**

```vue
<button>{{ resumeButtonLabel(msg) }}</button>
```

**可选增强**：后端 `metaMessage` 增加 `resumeMode: checkpoint|regenerate`（`prepareResume` 时写入），前端优先用 SSE meta，避免额外请求。

### 6.3 后端（可选）

`GenerationFlushScheduler.metaMessage` / `prepareResume`：

```java
map.put("resumeMode", planWorkflowResume ? "checkpoint" : "regenerate");
```

持久化到 `chat_message` 或在 INTERRUPTED 时写 Redis 一代，供刷新后仍正确。

### 6.4 验收

- Plan DAG 节点 paused → 按钮「继续执行」。
- ReAct 中断 → 按钮「重新生成」。
- Planner 中断（无 node paused）→ 「继续执行计划」或「重新生成」（与 §4 validated 是否存在一致）。

---

## 7. P1-A：ReAct 停止后步骤 lifecycle

### 7.1 改动

**`ProcessingStepMerger.pauseRunningWorkflowNodes`**

- 除 `node-*` 外，对 `phase ∈ { think, agent, generate }` 且 `lifecycle=running` 的步调用 `toPaused`（或统一 `interrupted` 专用 summary）。

**`processingSteps.ts` `pauseRunningWorkflowNodes`**

- 与后端对称：pause running 的 `think*` / `tool-*` / `agent` 步。

### 7.2 验收

ReAct 工具调用中停止 → think/tool 行显示「已暂停」或随消息 interrupted，不再 spinner。

---

## 8. P1-B：wfCtx 空时拒绝 Plan 续跑

### 8.1 改动

**`PlanWorkflowExecutor.resumePaused` 入口：**

```java
if (pausePhase == EXECUTING && !WorkflowContextCodec.hasNodes(checkpoint.wfCtxJson())) {
    // 尝试 execution_trace 回填（已有 WorkflowContextResumeSupport）
    if (still empty) {
        return Flux.just(StreamToken.content(
            "无法从检查点恢复执行上下文，请重新发送问题。"));
        // 或 markFailed + 不 increment 误导性 success
    }
}
```

**`ChatController.prepareResume`**

- 若检测到 wfCtx 空且为 EXECUTING checkpoint，可直接 409 + 明确 msg（可选，与软失败二选一）。

### 8.2 验收

模拟 wfCtx 为空 checkpoint → 续跑返回明确提示，answer 不 hallucinate 重查。

---

## 9. 任务拆分（实施顺序）

> **实施计划 SSOT:** [2026-06-26-pause-resume-consistency.md](../plans/2026-06-26-pause-resume-consistency.md)

| Task | 模块 | 估时 | 依赖 |
|------|------|------|------|
| T1 | `WorkflowCheckpoint` + codec 扩展 `pausePhase` / `pendingInteraction` | 0.5d | — |
| T2 | `GenerationJob` + `ExecutionPlanStore` Planner 阶段 markPaused | 1d | T1 |
| T3 | `PlanWorkflowExecutor.resumePaused` PLANNING 分支 | 1d | T2 |
| T4 | HITL/Recovery `pendingInteraction` 停止落库 | 1.5d | T1 |
| T5 | HITL/Recovery 续跑 re-await 路径 | 2d | T4 |
| T6 | 前端 `mapStepStatus` awaiting 优先于 paused | 0.5d | T4 |
| T7 | 前端 `resolveResumeMode` + 按钮文案 | 0.5d | T2 |
| T8 | ReAct pause 步骤对称 | 0.5d | — |
| T9 | wfCtx 空校验 | 0.5d | T1 |
| T10 | 单测 + 验收脚本 | 1d | T1–T9 |

**建议迭代：**

- **迭代 1（P0）**：T1–T3 + T6–T7（Planner + 按钮 + UI 优先级）
- **迭代 2（P0）**：T4–T5（HITL/Recovery）
- **迭代 3（P1）**：T8–T10

---

## 10. 测试与验收清单

| # | 场景 | 期望 |
|---|------|------|
| A1 | Plan 首 node running 中停止 → 继续执行 | 同一 node 续跑，不 replan |
| A2 | Planner LLM 中停止（validated 已有） | 继续执行计划，跳过 Planner |
| A3 | Planner 未产出停止 | 重新生成/重新规划 |
| A4 | HITL 等待停止 → 继续 | 确认框仍在，不先调 tool |
| A5 | Recovery 等待停止 → 继续 | 三按钮仍在 |
| A6 | ReAct 停止 | 按钮「重新生成」，think 不 running |
| A7 | terminate 后 | 无续跑按钮 |
| A8 | wfCtx 空 checkpoint | 明确错误，不 silent 错答 |

---

## 11. 合理保留（本方案不改）

- 用户终止、消息 completed 不可续跑
- 非最后 assistant / 后续有新 user 不可续跑
- `resumeCount` 上限
- `knowledge` 意图隐藏续跑
- Simple-LLM 拼接续写
- ReAct 整段软续跑（无逐步 checkpoint）

---

## 12. 风险与回滚

| 风险 | 缓解 |
|------|------|
| checkpoint JSON 膨胀 | `pendingInteraction` 仅必要字段；validated 快照可只存 planId 引用 |
| 续跑 re-await 与 orchestrator 多实例 | 继续用 Redis token；confirm API 无本地 waiter 时已有 warn 路径 |
| 旧 checkpoint 无 pausePhase | 默认 `EXECUTING`，与现网兼容 |

回滚：Nacos 开关 `agent.pause.resume-interaction-enabled=false` 时 T4–T5 退化为「重跑节点」。
