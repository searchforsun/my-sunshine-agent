# 3.9.5 暂停/续跑一致性 — 实施计划

> **For agentic workers:** 推荐 superpowers:subagent-driven-development，按 Task 顺序逐条执行；每 Task 完成后再勾选状态。  
> **设计 SSOT:** [2026-06-26-pause-resume-consistency-design.md](../specs/2026-06-26-pause-resume-consistency-design.md)  
> **阶段归属:** 阶段三收尾 · 检查门 §3.9.5

**Goal:** 统一 Plan/静态 Workflow 暂停与「继续生成」语义——Planner 阶段可续跑、HITL/Recovery 停止不丢交互、按钮文案分态、ReAct 步骤终态、wfCtx 空明确失败。

**Architecture:** 扩展 `WorkflowCheckpoint` JSON（`pausePhase` / `pendingInteraction`）；`GenerationJob.cancel` 先落库再 dispose；`PlanWorkflowExecutor.resumePaused` 三分支（PLANNING / EXECUTING / re-await）；前端 `resolveResumeMode` + DAG `mapStepStatus` awaiting 优先于 paused。

**Tech Stack:** JDK 21 · orchestrator :8200 · sunshine-ui Vue3 · Redis GenerationJob · MySQL `execution_plan.pause_checkpoint`

**前置（已完成）:** 节点 Recovery 重试/跳过/终止、基础 pause/resume、`nodeAttempts` SSE、`findResumableForMessage` → `resumePaused` 主路径。

**非目标:** ReAct 逐步 checkpoint（阶段四 4.7.5 TaskBoard）。

---

## 文件结构（边界锁定）

| 区域 | 创建 | 修改 | 测试 |
|------|------|------|------|
| 检查点模型 | `PendingInteraction.java`（或嵌 record） | `WorkflowCheckpoint.java`、`PlanJsonCodec.java` | `PlanJsonCodecTest.java` |
| 暂停落库 | — | `GenerationJob.java`、`ExecutionPlanStore.java`、`ProcessingStepMerger.java` | `GenerationJobPauseTest.java`（新建或扩展现有） |
| 续跑分支 | `ResumeInteractionHint.java`（可选） | `PlanWorkflowExecutor.java`、`WorkflowExecutor.java`、`ExecutionStreamContext.java` | `PlanWorkflowExecutorResumeTest.java` |
| HITL/Recovery | — | `HitlConfirmationService.java`、`WorkflowNodeRecoveryService.java`、`ToolNodeHandler.java` | `HitlConfirmationServiceTest.java`、`WorkflowNodeRecoveryServiceTest.java` |
| 配置 | — | `docs/nacos/sunshine-orchestrator.yaml`（`agent.pause.resume-interaction-enabled`） | — |
| 前端 | `sunshine-ui/src/api/resumeMode.ts` | `processingSteps.ts`、`planGraph.ts`、`ChatView.vue`、`chatSessions.ts` | 手动验收 A1–A8 |
| 验收脚本 | `scripts/verify_pause_resume_consistency.py` | — | — |

---

## 迭代排期

```
迭代 1（P0）  T1 → T2 → T3 → T6 → T7     Planner 暂停 + 按钮分态 + UI 优先级
迭代 2（P0）  T4 → T5                     HITL/Recovery pendingInteraction
迭代 3（P1）  T8 → T9 → T10               ReAct 步骤 + wfCtx 校验 + 全量验收
```

---

## Task T1: 检查点模型扩展

- [ ]

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/plan/WorkflowCheckpoint.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/plan/PendingInteraction.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/plan/PausePhase.java`（enum：`PLANNING` / `EXECUTING`）
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/plan/PlanJsonCodec.java`
- Create: `orchestrator/src/test/java/com/sunshine/orchestrator/plan/PlanJsonCodecCheckpointTest.java`

**Step 1:** 扩展 `WorkflowCheckpoint` record：

```java
public record WorkflowCheckpoint(
    String resumeNodeId,
    String wfCtxJson,
    PausePhase pausePhase,           // 缺省 EXECUTING（旧 JSON 兼容）
    PendingInteraction pendingInteraction  // 可 null
) {
    public WorkflowCheckpoint(String resumeNodeId, String wfCtxJson) {
        this(resumeNodeId, wfCtxJson, PausePhase.EXECUTING, null);
    }
}
```

**Step 2:** `PendingInteraction` 字段：`kind`（`hitl` | `recovery`）、`nodeId`、`errorMessage`、`hitlToolId`、`hitlParamsSummary`、可选 `recoveryAttempts` JSON。

**Step 3:** `PlanJsonCodec.checkpointToJson` / `checkpointFromJson`：
- 序列化 `pausePhase`、`pendingInteraction`
- **放宽** `resumeNodeId` 可为空串（PLANNING 阶段）
- 旧 JSON 无 `pausePhase` → 默认 `EXECUTING`

**Step 4:** 单测：旧 checkpoint 反序列化、PLANNING 空 resumeNodeId、带 pendingInteraction 往返。

```bash
mvn test -pl orchestrator -Dtest=PlanJsonCodecCheckpointTest
```

Expected: BUILD SUCCESS

---

## Task T2: Planner 阶段 markPaused

- [ ]

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/generation/GenerationJob.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/plan/ExecutionPlanStore.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/ProcessingStepMerger.java`（`emitPausedWorkflowSteps` 配套，见 T6 前置）

**Step 1:** `ExecutionPlanStore.markPaused` 放宽 status：`RUNNING` | `PAUSED` | **`VALIDATED`**（Planner 已校验、DAG 未跑）。

**Step 2:** `GenerationJob.persistWorkflowPauseIfNeeded` 扩展落库条件：

```
findByMessageId(messageId).filter(entity -> {
  status ∈ { running, validated }
  || (status == draft && validatedJson 非空)   // 按 entity 字段选最稳写法
})
```

**Step 3:** 推断 `pausePhase` 与 `resumeNodeId`：

| 条件 | pausePhase | resumeNodeId |
|------|------------|--------------|
| `currentNodeId` 或 `findLastRunningWorkflowNodeId` 非空 | EXECUTING | 该 nodeId |
| `validated_json` 已有、无 running node | PLANNING | `def.linearOrder()[0]` 或首个业务节点 |
| 否则 | PLANNING | `""` |

**Step 4:** `wfCtxJson`：EXECUTING 走现有 `WorkflowPauseService`；PLANNING 允许 `{}`。

**Step 5:** `emitPausedWorkflowSteps`：若仅有 `plan` 步 running、无 `node-*` running，**跳过**对 plan 步 `toPaused`（保持 running / interrupted 语义，避免 DAG 误导黄节点）。

```bash
mvn test -pl orchestrator -Dtest=ExecutionPlanStoreTest,GenerationFlushSchedulerTest -q
```

Expected: 现有测试绿；新增/扩展 pause 单测覆盖 VALIDATED 态 markPaused。

---

## Task T3: PlanWorkflowExecutor PLANNING 续跑分支

- [ ]

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/PlanWorkflowExecutor.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/WorkflowExecutor.java`（注释/守卫：PLANNING 空 resumeNodeId 不进入 `resumeDynamicDefinition`）

**Step 1:** `resumePaused` 入口读取 checkpoint：

```java
WorkflowCheckpoint cp = executionPlanStore.loadCheckpoint(entity);
if (cp.pausePhase() == PausePhase.PLANNING) {
    markResumed(planId);
    if (StringUtils.hasText(entity.getValidatedJson())) {
        PlanJson enriched = ...;
        return runValidatedPlan(ctx, planId, session, enriched, 1);  // 跳过 Planner
    }
    return planAndExecute(ctx, planId, 1, null);  // 重新规划
}
// 现有 resumeDynamicDefinition 路径（含 pendingInteraction 占位，T5 实现）
```

**Step 2:** `WorkflowExecutor.resumeDynamicDefinition`：若 `pausePhase==PLANNING && resumeNodeId 空` → 直接 return empty（由上层处理）。

**Step 3:** 单测 `PlanWorkflowExecutorResumeTest`：
- validated 已有 + PLANNING checkpoint → 不调用 `workflowPlanner.plan`
- validated 空 + PLANNING → 调用 `planAndExecute`

```bash
mvn test -pl orchestrator -Dtest=PlanWorkflowExecutorResumeTest
```

Expected: BUILD SUCCESS

---

## Task T4: HITL/Recovery 停止落库 pendingInteraction

- [ ]

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/generation/GenerationJob.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/ProcessingStepMerger.java`
- Modify: `docs/nacos/sunshine-orchestrator.yaml`（`agent.pause.resume-interaction-enabled: true`）

**Step 1:** `GenerationJob.cancel` → `persistWorkflowPauseIfNeeded` 内扫描 `stepsBuffer`：
- `metadata.hitl.status == awaiting` → 构建 `PendingInteraction(kind=hitl, ...)`
- `metadata.recovery.status == awaiting` → `kind=recovery`

**Step 2:** 若存在 pendingInteraction：
- 写入 checkpoint（`pausePhase=EXECUTING`，`resumeNodeId=nodeId`）
- **不对**该 `node-*` 步调用 `toPaused`（HITL 保持 running+metadata；Recovery 保持 error+metadata）
- 其他 running `node-*` 仍 `pauseRunningWorkflowNodes`（排除交互节点 id）

**Step 3:** `ProcessingStepMerger.pauseRunningWorkflowNodes` 增加 skip 集合参数或内部检测 awaiting metadata，跳过交互节点。

**Step 4:** Nacos 开关 `agent.pause.resume-interaction-enabled`（默认 true）；false 时 T4–T5 行为退化为现有「重跑节点」。

```bash
python scripts/sync_nacos.py --data-id sunshine-orchestrator.yaml
mvn test -pl orchestrator -Dtest=GenerationJobPauseInteractionTest
```

Expected: cancel 时 steps 中 awaiting 步 lifecycle 不变；DB checkpoint 含 pendingInteraction。

---

## Task T5: 续跑 re-await 路径

- [ ]

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/PlanWorkflowExecutor.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/WorkflowExecutor.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/ExecutionStreamContext.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/hitl/HitlConfirmationService.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/hitl/WorkflowNodeRecoveryService.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/handler/ToolNodeHandler.java`

**依赖:** T1、T4；Nacos `resume-interaction-enabled=true`

**Step 1:** `ExecutionStreamContext` 增加可选 `ResumeInteractionHint`（从 checkpoint.pendingInteraction 注入）。

**Step 2:** `PlanWorkflowExecutor.resumePaused`：

```
if (checkpoint.pendingInteraction() != null && resumeInteractionEnabled) {
  ① 恢复 wfCtx（WorkflowContextResumeSupport）
  ② 下发 PROGRESS step（重建 awaiting metadata，来自 checkpoint + steps_json）
  ③ HitlConfirmationService.resumeAwaitingFromCheckpoint(...) 或
     WorkflowNodeRecoveryService.resumeAwaiting(...)
  ④ 用户决策后走现有 approve / RETRY / SKIP 分支
} else {
  resumeDynamicDefinition(...)
}
```

**Step 3:** `WorkflowExecutor.executeNode`：若 `streamCtx.resumeInteraction()` 非空且 nodeId 匹配 → 跳过 `runNode` 首次执行，直接进入 interaction resume。

**Step 4:** `HitlConfirmationService.resumeAwaitingFromCheckpoint`：register 新 Redis token + 内存 Future；**不**先调 tool-manager。

**Step 5:** `WorkflowNodeRecoveryService.resumeAwaiting`：attach awaiting metadata + 阻塞 `Future.get()`。

**Step 6:** 单测 + 集成：
- HITL cancel → resume → `awaitConfirmation` 再次被调用
- Recovery cancel → resume → 三按钮 metadata 仍在

```bash
mvn test -pl orchestrator -Dtest=HitlConfirmationServiceTest,WorkflowNodeRecoveryServiceTest,WorkflowExecutorTest
```

Expected: BUILD SUCCESS；resume 不触发 tool 重复执行（mock 断言 call count）。

---

## Task T6: 前端 awaiting 优先于 paused

- [ ]

**Files:**
- Modify: `sunshine-ui/src/utils/planGraph.ts`
- Modify: `sunshine-ui/src/api/processingSteps.ts`（`pauseRunningWorkflowNodes` 对称 skip awaiting 节点）
- Modify: `sunshine-ui/src/components/plan/PlanNodeDrawer.vue`（必要时：paused 文案不与 awaiting 冲突）

**Step 1:** `mapStepStatus` 在 `lifecycle === 'paused'` **之前**增加：

```ts
if (isRecoveryAwaiting(step)) return 'error'  // 或专用 'recovery_awaiting'，与现有样式一致
```

当前已有 `stepHasHitlAwaiting` → `awaiting_confirm`；确认 Recovery awaiting 不被 paused 覆盖。

**Step 2:** `pauseRunningWorkflowNodes`（前端 stop 本地态）：跳过 `metadata.hitl.status===awaiting` 与 `metadata.recovery.status===awaiting` 的 `node-*`；不对仅有 `plan` 步 running 标 paused。

**Step 3:** 手动：HITL 紫呼吸 / Recovery 红呼吸 + 三按钮，stop 后刷新页面状态保持。

---

## Task T7: 续跑按钮分态

- [ ]

**Files:**
- Create: `sunshine-ui/src/api/resumeMode.ts`
- Modify: `sunshine-ui/src/views/ChatView.vue`
- Modify: `sunshine-ui/src/api/chatSessions.ts`（stop 后本地 steps 与 resumeMode 一致）
- Optional Modify: `orchestrator/.../GenerationFlushScheduler.java`、`ChatController.java`（`metaMessage.resumeMode`）

**Step 1:** 实现 `resolveResumeMode(msg): 'checkpoint' | 'planning' | 'regenerate'`：

| 条件 | mode | 按钮文案 |
|------|------|----------|
| `node-*` paused 或 awaiting interaction | checkpoint | 继续执行 |
| `plan` 步存在且 interrupted、无 node paused | planning | 继续执行计划 |
| 无 resumable plan | regenerate | 重新生成 |
| `intent === 'knowledge'` | — | 不显示 |

**Step 2:** `ChatView.vue`：`canResume` 逻辑不变；按钮文案 `resumeButtonLabel(msg)` 替代硬编码「继续生成」。

**Step 3（可选）:** 后端 INTERRUPTED 时 meta 写入 `resumeMode`；前端优先 SSE meta，减少启发式误判。

**Step 4:** 手动验收 A6：ReAct 中断 →「重新生成」；Plan node paused →「继续执行」。

---

## Task T8: ReAct 停止后步骤 lifecycle

- [ ]

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/ProcessingStepMerger.java`
- Modify: `sunshine-ui/src/api/processingSteps.ts`

**Step 1:** `pauseRunningWorkflowNodes`（后端）扩展：对 `phase ∈ { think, agent, generate }` 且 `lifecycle=running` 的步（含 `think-*`、`tool-*`）调用 `toPaused`。

**Step 2:** 前端 `pauseRunningWorkflowNodes` 对称处理 ReAct 步。

**Step 3:** 手动：ReAct 工具调用中 stop → think/tool 行显示「已暂停」，无 spinner。

```bash
mvn test -pl orchestrator -Dtest=ProcessingStepMergerTest
```

Expected: BUILD SUCCESS

---

## Task T9: wfCtx 空拒绝 Plan 续跑

- [ ]

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/PlanWorkflowExecutor.java`
- Optional Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/controller/ChatController.java`

**Step 1:** `resumePaused` 入口，`pausePhase==EXECUTING` 时：

```java
if (!WorkflowContextCodec.hasNodes(checkpoint.wfCtxJson())) {
    WorkflowContextResumeSupport.prepare(..., entity.getExecutionTrace()); // 已有回填
}
if (still empty) {
    return Flux.just(StreamToken.content("无法从检查点恢复执行上下文，请重新发送问题。"));
}
```

**Step 2（可选）:** `prepareResume` 检测 wfCtx 空 → HTTP 409 + 明确 message（与 content 软失败二选一，建议软失败 + 日志）。

**Step 3:** 单测：空 wfCtx checkpoint → 不进入 `resumeDynamicDefinition`；answer 为固定提示文案。

```bash
mvn test -pl orchestrator -Dtest=PlanWorkflowExecutorResumeTest#resumeRejectedWhenWfCtxEmpty
```

Expected: BUILD SUCCESS

---

## Task T10: 单测汇总 + 验收脚本

- [ ]

**Files:**
- Create: `scripts/verify_pause_resume_consistency.py`
- Modify: `docs/superpowers/specs/phase3-production-hardening-design.md` §6（勾选 3.9.5 检查门）
- Modify: `docs/implementation-plan.md`（3.9.5 状态 ⬜→✅）

**Step 1:** 验收脚本覆盖设计 §10 A1–A8（需 live 中间件：login → send plan → stop → resume）：

| # | 场景 | 断言 |
|---|------|------|
| A1 | 首 node running stop → resume | 同一 node，无 replan |
| A2 | Planner 中 stop（validated 已有） | 跳过 Planner |
| A3 | Planner 未产出 stop | 重新规划 |
| A4 | HITL awaiting stop → resume | 确认框仍在 |
| A5 | Recovery awaiting stop → resume | 三按钮仍在 |
| A6 | ReAct stop | 按钮「重新生成」 |
| A7 | terminate 后 | 无续跑按钮 |
| A8 | wfCtx 空 checkpoint | 明确错误文案 |

**Step 2:** 全量 orchestrator 相关测试：

```bash
mvn test -pl orchestrator -Dtest=PlanJsonCodecCheckpointTest,PlanWorkflowExecutorResumeTest,ProcessingStepMergerTest,HitlConfirmationServiceTest,WorkflowNodeRecoveryServiceTest,WorkflowExecutorTest,GenerationFlushSchedulerTest
python scripts/phase2_agent_demo.py --suite workflow
```

Expected: 单测绿；workflow suite 不退化。

**Step 3:** 编译部署：

```bash
mvn compile -pl orchestrator -am -q
python scripts/sync_nacos.py --data-id sunshine-orchestrator.yaml
python scripts/start.py --only orchestrator
```

**Step 4:** 更新检查门与 `implementation-plan.md` 进度行。

---

## 回滚

| 开关 | 效果 |
|------|------|
| `agent.pause.resume-interaction-enabled=false` | T4–T5 退化为重跑节点；T1–T3/T7–T9 仍生效 |
| 还原 checkpoint codec | 仅当未部署 T1 时可单独回滚 JSON 格式 |

---

## 检查门（3.9.5）

- [ ] Planner 阶段 stop 可续跑（validated 跳过 Planner）
- [ ] HITL/Recovery 停止后续跑恢复同一交互
- [ ] 无 checkpoint 按钮为「重新生成」；有 checkpoint 为「继续执行」/「继续执行计划」
- [ ] ReAct stop 后 think/tool 非 running
- [ ] wfCtx 空续跑明确失败
- [ ] `phase2_agent_demo.py --suite workflow` PASS

---

## 参考代码锚点

| 能力 | 当前入口 |
|------|----------|
| 暂停落库 | `GenerationJob.persistWorkflowPauseIfNeeded()` |
| 续跑路由 | `ChatController.resolveChunkFlux` → `findResumableForMessage` |
| Plan 续跑 | `PlanWorkflowExecutor.resumePaused()` |
| DAG 续跑 | `WorkflowExecutor.resumeDynamicDefinition()` |
| 前端 stop 本地态 | `processingSteps.pauseRunningWorkflowNodes` |
| 续跑按钮 | `ChatView.canResume` + 硬编码「继续生成」 |
| DAG 状态 | `planGraph.mapStepStatus` |
