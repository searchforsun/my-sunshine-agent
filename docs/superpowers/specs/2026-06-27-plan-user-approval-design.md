# 动态 Plan 用户确认（HITL 扩展）

> **状态**：✅ 已实现（2026-06-27）  
> **配置 SSOT**：`docs/nacos/sunshine-orchestrator.yaml` → `agent.execution.plan-workflow.approval`  
> **后端**：`PlanApprovalService`、`PlanWorkflowExecutor.runUserApprovalLoop`  
> **前端**：`PlanWorkflowPanel`、`PlanApprovalActions`、`PlanDagGraph`、`PlanNodeDrawer`  
> **关联**：[plan-workflow-retry-degradation.md](../../routing/plan-workflow-retry-degradation.md) §8

## 1. 行为概要

Planner 校验通过后、**节点执行前**，阻塞等待用户：

| 操作 | 后端 | 前端 |
|------|------|------|
| **确认执行** | `markValidated` → 进入 DAG 执行 | 确认行折叠为「已确认执行」 |
| **重新生成** | `replanWithUserHint` → 再校验 → 新一轮确认 | 图区 loading；确认行显示「正在重新生成」 |
| **超时** | 默认 `on-timeout: fallback_react` | 「确认超时」 |

多轮重新生成保留 **rounds[]** 历史；每轮独立 `CollapsibleConfirmPanel` 一行。

## 2. API 与 SSE

| 项 | 说明 |
|----|------|
| `POST /api/chat/confirm-plan` | BFF → Orchestrator；body：`token`、`action`（`approve` \| `regenerate`）、可选 `hint` |
| `step.metadata.planApproval` | `status`、`token`、`expiresAt`、`rounds[]`、`planGraph`（内联拓扑，确认前可无 `execution_plan_id`） |
| plan 步 `summary.active` | 重新规划期：`正在根据修改意见重新规划…`（前端 `isPlanRegenerating`） |
| flush | 阻塞前必须 flush SSE（同 HITL），否则前端拿不到 `token` |

`execution_plan_id` 在 **`markValidated`（用户确认后）** 才写入 `chat_message`；待确认期 `planId` 来自 plan 步 `detail` 的 `planId=` 或 `planApproval.token` 兜底（放大视图用 `approval:{token}`）。

## 3. 确认框 UI（与 HITL / Recovery 一致）

- 组件：`CollapsibleConfirmPanel` + `PlanApprovalActions`
- 折叠：单行 `summary · detail`（链路概要）；无 accent 黄底
- 待确认：默认展开，含修改意见 textarea +「重新生成」「确认执行」
- 已决态：默认折叠

**重新生成进行中**（最后一轮 `regenerated` 且 `isPlanRegenerating`）：

- 折叠行：`执行计划确认 · 正在重新生成`（非「已重新生成」）
- 不附带旧链路 detail

规划完成、出现新一轮 `awaiting` 后，历史轮显示：`执行计划确认 · 已重新生成 · …链路…`

## 4. 流程图区（`PlanDagGraph`）

| 规则 | 实现 |
|------|------|
| 重新生成 loading | **仅** `plan-dag-scroll` 内遮罩 + 转圈「重新生成中…」；`plan-dag-track` `visibility:hidden`；**不改卡片背景色** |
| 确认框 | 重新生成期间 **不** 额外 loading UI |
| 放大按钮 | 卡片 **右上角**；`padding-right: 42px` 预留区，DAG **不可滚入** 按钮区 |
| 重新生成中 | **隐藏** 放大按钮（`:show-expand="!isRegenerating"`） |
| 待执行文案 | 业务节点 pending：**等待中**（非「待执行」） |

放大层 `PlanDagExpandLayer` 同步 `loadingLabel`；`fluid` 模式无内联放大钮。

## 5. 节点抽屉（`PlanNodeDrawer`）

| 节点 | 状态来源 | 展示 |
|------|----------|------|
| **开始** | DAG 节点 `status`（**不**跟 plan 步 `running`） | `pending` → **等待中**；已进入执行 → **已通过** |
| answer / llm / 业务 | step + node 对齐 | 同原约定 |

## 6. 关键文件

| 层 | 路径 |
|----|------|
| 配置 | `docs/nacos/sunshine-orchestrator.yaml` |
| 服务 | `orchestrator/.../plan/PlanApprovalService.java` |
| 执行 | `orchestrator/.../execution/PlanWorkflowExecutor.java` |
| API | `orchestrator/.../controller/ChatController.java`（`confirm-plan`） |
| SSE 合并 | `orchestrator/.../agent/ProcessingStepMerger.java` |
| 面板 | `sunshine-ui/src/components/plan/PlanWorkflowPanel.vue` |
| 确认 | `sunshine-ui/src/components/plan/PlanApprovalActions.vue` |
| 折叠框 | `sunshine-ui/src/components/operation/CollapsibleConfirmPanel.vue` |
| DAG | `sunshine-ui/src/components/plan/PlanDagGraph.vue` |
| 抽屉 | `sunshine-ui/src/components/plan/PlanNodeDrawer.vue` |
| 状态 | `sunshine-ui/src/api/planApprovalSteps.ts` |

## 7. 验收要点

1. Plan 路由命中后，DAG 展示 + 下方确认框；点「确认执行」后开始 node 步。
2. 点「重新生成」：图区转圈、放大钮隐藏；确认行「正在重新生成」；完成后新 DAG + 新 awaiting 轮。
3. 横向滚动 DAG 时，末节点不进入右上角按钮区。
4. 点开「开始」：plan 执行中 **不** 显示「执行中」。
5. 待执行业务节点角标为「等待中」。

```bash
# 改配置后
python scripts/sync_nacos.py --data-id sunshine-orchestrator.yaml
# 重启 orchestrator :8200；前端 dev 刷新
```
