# Plan-Workflow 重试与降级

> **SSOT**：配置 `docs/nacos/sunshine-orchestrator.yaml` → `agent.execution.plan-workflow` / `agent.prompt.mode-overlays.react`  
> **代码**：`PlanWorkflowExecutor`、`WorkflowExecutor`、`NodeRetryExecutor`、`PlanExecutionAuditService`  
> **验收**：本文 §验收提示词 + [routing-golden-set.md](./routing-golden-set.md) §A

## 1. 分层策略

| 阶段 | 机制 | 配置键 |
|------|------|--------|
| **S1 Planner 调用** | 网关 5xx/超时等同参重试 | `planner.max-attempts` / `backoff-ms` |
| **S2 规划校验** | 带错误反馈 **Replan**（非盲重调） | `replan.max-attempts` / `user-feedback-template` |
| **S2 耗尽** | 降级 **ReAct** | `PlanTimeline.planRejectedStep` |
| **S3 节点执行** | 按错误类型同参重试 | `node.defaults` / `by-type` / 节点 `retry.*` params |
| **S3 节点失败** | `on-failure` 策略 | `continue` / `fail_fast` / `skip` / `fallback_react` |
| **S4 answer** | 上游失败注入 prompt | `answer.upstream-failure-line` + `UpstreamOutputResolver` |
| **S5 整单** | 关键 tool 失败等 | `critical-tools` + `critical-on-failure`（默认 `fail_fast`） |
| **ReAct 降级** | 注入已成功节点上下文 | `fallback-react.inject-partial-context` |

**原则**：规划期纠错走 Replan；执行期同参重试走 `NodeRetryExecutor`；**禁止**执行期 Enricher 偷偷改 Planner 图。

## 2. Plan 终态

| status | 含义 |
|--------|------|
| `completed` | 全部业务节点 + answer 成功 |
| `completed_with_errors` | 有节点 failed，但 answer 已输出 |
| `failed` | `fail_fast` 或 answer 失败等 |
| `rejected` | 规划/校验耗尽，已降级 ReAct |
| `degraded_react` | 执行中 `fallback_react`，改由 ReAct 接续 |

## 3. 持久化与审计

**Flyway V8**：`execution_plan.planner_attempts`、`replan_count`。

**execution_trace**（`PlanNodeTrace`）扩展：

- `attemptCount`、`onFailure`
- `attempts[]`：`attemptNo` / `status` / `errorClass` / `summary` / 时间戳

**审计事件**（`PlanExecutionAuditService`）：`plan.planner_attempt`、`plan.node.attempt`、`plan.fallback_react`。

## 4. 可视化

| 位置 | 内容 |
|------|------|
| plan 步 `detail` | `planId` \| `replanCount` \| `chain` |
| plan 步 `after` | 含「规划经 N 次修正后开始执行」 |
| DAG 节点角标 | `attemptCount > 1` → `×N` |
| `PlanNodeDrawer` | 「执行记录」列表 |
| `/plans/:planId` | `planner_attempts` + `execution_trace` |

## 5. ReAct 专属提示词

与 plan-workflow **引擎重试无关**；仅引导模型在 `max-iters` 内是否改参再调工具。

配置：`agent.prompt.mode-overlays.react`（全局 `agent.system-prompt` 管输出格式与勿编造）。

## 6. 默认节点策略摘要

| 类型 | max-attempts | 备注 |
|------|:------------:|------|
| rag | 1 | empty-recall 二次检索单独记账，不计入 attempt |
| tool | 2 | 仅 TIMEOUT / 5xx 等可重试 |
| agent | 1 | 子 Agent 内部 `max-iters` |
| answer | 2 | 默认 `on-failure: fail_fast` |
| critical tool | — | `list_finance_messages` 等 → `fail_fast` |

## 7. 验收提示词

### 7.1 基线（成功路径）

```
先查企业差旅报销制度，再列出待审批的报销单，最后做合规审查并给建议
```

预期：`completed`；DAG 业务链；`node-answer` 流式输出。

### 7.2 Replan / 降级 ReAct

```
先检索差旅报销制度，再查待审批报销单，并对每条做合规分析
```

- Replan：`planner_attempts` 有记录；plan 步 `replanCount`
- 校验仍失败：plan 步「降级为 ReAct」，无 DAG，`status=rejected`

### 7.3 节点重试

```
先检索报销制度，再查询待审批报销单 status=pending，汇总后给处理建议
```

（可配合短暂停 tool-manager / 下游制造超时）

预期：DAG 角标 `×2`；`execution_trace.attempts[]`。

### 7.4 关键 tool fail_fast

停 finance 或 tool-manager 后：

```
先检索制度，再查待审批报销单并给出逐条结论
```

预期：`status=failed`；不进入 `completed_with_errors`。

### 7.5 非关键失败 + 残缺 answer

部分 tool 失败且 `on-failure: continue`：

预期：`completed_with_errors`；answer 上游含「执行失败…已尝试 N 次」。

### 7.6 fallback_react（需 Nacos `critical-on-failure: fallback_react`）

预期：`degraded_react`；接续 ReAct 且注入已成功节点 output。

### 7.7 ReAct 多工具（mode-overlays.react）

```
帮我查公司差旅报销制度住宿标准，再列出待审批报销单，对比是否超标
```

预期：`think → tool → think-2 → tool-2`；工具失败时模型可能改参再调（非引擎重试）。

## 8. 单测

```bash
mvn test -pl orchestrator -Dtest=PlanWorkflowExecutorTest,WorkflowExecutorTest,NodeRetryExecutorTest,ExecutionErrorClassifierTest
```

## 9. 部署

```bash
python scripts/sync_nacos.py --data-id sunshine-orchestrator.yaml
mvn package -pl orchestrator -am -DskipTests
# 重启 orchestrator :8200（Flyway V8 首次启动自动迁移）
```
