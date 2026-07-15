# Workflow Loop 容器 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (or subagent-driven-development). Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地 `loop` 容器节点（预检测 while + 线性 body + `maxIterations` / `onMaxIterations`），引擎 + Studio + 校验 + 单测。

**Architecture:** 扁平 PlanJson；body 节点带 `parentId`；调度 `Step.Loop`；条件复用 `EdgeConditionEvaluator`；Vue Flow 用 parent 容器节点渲染。

**Tech Stack:** Java/Spring（orchestrator + workflow-manager）、Vue3 + Vue Flow、Jest-free Java unit tests

**Spec:** `docs/superpowers/specs/2026-07-14-workflow-loop-container-design.md`

---

## File map

| Area | Files |
|------|--------|
| Model | `PlanNode.java`, `PlanJsonParser.java`, `PlanJsonCodec.java`, `WorkflowNodeType.java` |
| Schedule/Exec | `PlanExecutionSchedule.java`, `WorkflowExecutor.java`, `LoopNodeHandler.java`, `NodeHandlerRegistry` |
| Validate | `PlanValidator.java`, `WorkflowPlanValidator.java` |
| FE types/utils | `workflows.ts`, `workflowPlan.ts`, `workflowPlanValidation.ts`, `workflowDagLayout.ts`, `workflowNodeParams.ts`, `workflowGateway.ts` / container helper |
| FE UI | `WorkflowFlowNode.vue`, `WorkflowDagEditor.vue`, `WorkflowStudioPropsAside.vue`, `WorkflowStudioCanvasToolbar.vue`, `PlanNodeIcon.vue`, `executionPlans.ts` |
| Docs/seed | `CLAUDE.md`, `docs/workflow/README.md`, optional SQL seed later |

---

### Task 1: PlanNode.parentId + loop type + parse/codec

**Files:**
- Modify: `orchestrator/.../plan/PlanNode.java`
- Modify: `PlanJsonParser.java`, `PlanJsonCodec.java`
- Modify: `execution/WorkflowNodeType.java`, `WorkflowTimelineLabels.java`
- Test: `PlanJsonParserTest.java`, `PlanJsonCodecTest.java`

- [ ] Add optional `parentId` to `PlanNode`; parse/serialize when present
- [ ] Register `LOOP("loop")` in `WorkflowNodeType` (exec + timeline step)
- [ ] Unit: round-trip JSON with `parentId` + type `loop`

### Task 2: validateLoopTopology (orchestrator + workflow-manager)

**Files:**
- Modify: `PlanExecutionSchedule.java` (or dedicated validator helper)
- Modify: `PlanValidator.java`
- Modify: `workflow-manager/.../WorkflowPlanValidator.java`
- Test: `PlanExecutionScheduleTest.java`, `WorkflowPlanValidatorTest.java`

- [ ] Rules: body non-empty linear rag/tool/agent; no cross-parent edges; loop out-degree=1; maxIterations 1–5; onMaxIterations ∈ {fail_fast,exit,fallback_react}; condition complete
- [ ] Outer schedule skips nodes with `parentId`

### Task 3: Schedule Step.Loop + executeLoop

**Files:**
- Modify: `PlanExecutionSchedule.java`, `WorkflowExecutor.java`
- Create: `execution/handler/LoopNodeHandler.java` (mark routed/iterating if needed)
- Test: `PlanExecutionScheduleTest`, new `LoopExecutionTest` or extend `WorkflowExecutorTest`

- [ ] `build()` emits `Loop(loopId, bodyOrder, exitSuccessor)`
- [ ] Runtime: while pre-test; run body; onMaxIterations strategies
- [ ] Write `loop.output` from last body end node; Timeline one `node-{loopId}` (+ subSteps if feasible in same pass)

### Task 4: Studio model + canvas container

**Files:**
- Modify: `workflows.ts` (`parentId?`), `workflowPlan.ts`, `workflowDagLayout.ts`, `workflowPlanValidation.ts`, `workflowNodeParams.ts`
- Modify: `WorkflowFlowNode.vue`, `WorkflowDagEditor.vue`, `WorkflowStudioCanvasToolbar.vue`

- [ ] Add loop to palette; create container with default size; body nodes `parentNode` in Vue Flow
- [ ] Connection rules: no cross-frame; body only rag/tool/agent
- [ ] Local validation mirrors backend

### Task 5: Studio props + reconcile left

**Files:**
- Modify: `WorkflowStudioPropsAside.vue`, `workflowFieldHelp.ts`, `workflowPlan.ts` (`loopConditionLeft` / reconcile)

- [ ] Side panel: condition left readonly auto, op/right, maxIterations, onMaxIterations, retry
- [ ] Reconcile syncs `condition.left` like exclusive

### Task 6: Chat display + docs status

**Files:**
- Modify: `PlanNodeIcon.vue`, `executionPlans.ts`, `PlanNodeDrawer.vue` (optional loop summary)
- Modify: `CLAUDE.md`, `docs/implementation-plan.md`, spec status → 已确认/实施中

- [x] Icon + type label「循环」
- [x] Mark 4.13.7 loop ✅ when Live or unit gates pass

### Task 7: Verification

- [x] `mvn -pl orchestrator,workflow-manager -am test` for touched tests
- [x] Manual or Live smoke if services up
- [x] **2026-07-15**：当前形态收口；v1 非目标不做（见 loop / Studio 详设）

**Commits:** only when user requests.

---

## Spec coverage

| Spec requirement | Task |
|------------------|------|
| parentId flat model | 1 |
| while + condition params | 3, 5 |
| maxIterations / onMaxIterations | 2, 3, 5 |
| linear body only | 2, 4 |
| Studio container | 4, 5 |
| Timeline one step | 3 |
| Non-goals | skipped |
