# 意图识别可观测（v6 语义收敛 + routingTraces）设计

> **状态**：✅ 已实现（后端 + 前端代码完成，Live 待验收）
> **日期**：2026-08-13
> **编号**：阶段四增量（前端意图展示语义收敛 + 路由可观测）
> **前置**：[unified-routing v6](./2026-07-29-unified-routing-design.md)（模式锁定 + 轨 A/B 收集）· [rebuild](./2026-08-05-planner-executor-rebuild-design.md) H-5（`pro`→harness）
> **一句话**：后端在 `ExecutionPlan` 增加 `routingTraces[]`（每层命中记录），intent 步与路由试跑面板按 **v6 语义**（模式锁定 + 轨内绑定）展示；替换仍停在 `plan-workflow`/`react` 旧枚举的文案与字段。

## 1. 背景与问题

路由后端（routing v6）已改为 **用户显式锁定 executionMode + 轨 A/B 收集绑定**，但**意图展示仍停留在 v5 语义**：

| 位置 | 现状 | 错位 |
|------|------|------|
| Chat 时间线 `intent` 步 | 仅占位，不展示绑定原因 | 后端已下发 `routingReason`，前端未渲染 |
| `RoutingDryRunPanel` | 标签 `plan-workflow`/`react`/`would_llm`/`reactPromptId` | v6 无 `plan-workflow`/`react`；`reactPromptId` 已退役；「未命中交给大模型做意图分类」暗示 LLM 可改模式（已禁止） |
| `PromptPrinciplesPanel` 意图节 | L3「未命中 → intent.classifier」；「workflow / plan-workflow / react」分发 | 同上；且该节把**上下文分层 L1/L2/L3** 与**意图链**混在一页 |

**根因**：v6 契约改在路由层，展示层未同步。用户无法从时间线看出「这个 skill/agent/workflow 为什么被绑定」，也无法验证「模式是否真的被锁定、轨内是否守规矩」。

## 2. 目标

1. **intent 步语义收敛**（方案 A）：主行统一状态文案（`已完成意图识别`），不再按模式拼 v5 旧文案（`自主推理`/`动态规划`）；绑定细节交由抽屉展示。
2. **路由链路可观测**（方案 B）：后端下发 `routingTraces[]`，intent 步抽屉直接展示**用户可读的识别过程**（label+detail 已翻译，无内部 layer/id）。
3. **试跑面板语义收敛**：标签改 `fast`/`pro`/`workflow`，去掉 `would_llm`/`reactPromptId`，判定结果改为「L0 硬绑定 / 规则命中 / L3 补绑定」。
4. **原理分析页收敛**：意图节按 v6 文案重写，删 `plan-workflow`/`react` 分发措辞；上下文分层内容保留（那是另一码事）。

**非目标**：
- 不做完整可视化路由树面板（方案 C）
- 不改路由逻辑本身（只加可观测字段）
- 不删动态 Plan-Workflow 源码（阶段 D / R-4）

## 3. 后端：`routingTraces` 与 intent 步

### 3.1 `ExecutionPlan` 增加 `routingTraces`

```java
public record RoutingTrace(String layer, String label, String detail) {}
```

- `layer`：`mode` / `track` / `L0` / `rule` / `L3` / `final`
- `ExecutionPlan` 增加 `List<RoutingTrace> routingTraces`（可空；Jackson 序列化为 `routingTraces`，空则省略）
- 兼容：老 JSON/DB 无该字段 → 反序列化 `null`，前端兜底「无轨迹」

### 3.2 `ForcedExecutionRouter` 记录 trace

在 `BindingAcc` 累积 trace，`toPlan()` 带入。**文案面向用户**：label/detail 全部翻译为可理解过程描述，不暴露 layer 与内部 id；skill/agent/workflow 经 Catalog 解析为 displayName（未命中时回退原 id）。

| 时机 | layer | label | detail |
|------|-------|-------|--------|
| 入口 | `mode` | `处理方式` | `按您选择的「快速 / 专业 / 工作流」模式处理` |
| 轨判定 | `track` | `匹配方式` | `自动匹配技能与助手` 或 `直接按流程模板执行` |
| `$agent` | `L0` | `绑定助手` | `使用助手「{displayName}」处理` |
| `/skill` | `L0` | `绑定技能` | `使用技能「{displayName}」处理` |
| `#workflow` | `L0` | `绑定流程` | `使用流程「{displayName}」` |
| 规则命中 | `rule` | `智能匹配` | `命中常用处理规则` |
| L3 调用 | `L3` | `意图识别` | `识别出应使用技能/助手「{displayName}」` 或 `识别为流程「{displayName}」` |
| 最终 | `final` | `执行方案` | `使用技能/助手「{displayName}」处理` / `将执行「{displayName}」流程` / 未绑定 → `交由智能体自主分析作答`（fast）或 `动态规划多步执行`（pro） |

- `skillBindingRoutingPolicy` / `agentBindingRoutingPolicy` / `workflowBindingRoutingPolicy` 命中时，由 `ForcedExecutionRouter` 读 plan.params 记 trace（避免改各 Policy 签名）。
- `applyTrackRule` 命中 `rule` 时记 trace（`ruleId`）。
- `intentRouter.classifyPlan` 之后记 `L3` trace（LLM 填入的 `skillId` / `workflowId` / `reason`）。

### 3.3 intent 步下发

- intent 步 `StepMetadata` 增加 `routingTraces` 字段；`ProcessingStepSerde` 写出。
- intent 步主行统一状态文案（`已完成意图识别`），`routingTraces` 进 `metadata` 供抽屉展示可读过程。

## 4. 前端

### 4.1 intent 步展示（方案 A）

- intent 步主行统一状态文案：`before=识别用户意图` → `active=正在识别用户意图...` → `after=已完成意图识别`（`timeline.intent` Catalog 配置）。
- 不再展示绑定摘要行/`routingReason` 主行；识别过程整体收敛进抽屉（见 4.2）。

### 4.2 intent 步抽屉（方案 B）

- 复用 `OperationStack` 行展开/详情抽屉机制；intent 步存在 `metadata.routingTraces` 时直接展示逐条 trace 行（无标题；对齐 RAG「检索过程」先例）。
- 区块：逐条 `label → detail`（label 为可读动作词、detail 为可读结论），无边框色块。

### 4.3 `RoutingDryRunPanel` 收敛

- `MODE_LABELS` 改：`fast=快速 / pro=专业 / workflow=工作流`。
- 判定结果三态：`L0 硬绑定` / `规则命中` / `L3 补绑定`（`stage`：`l0`/`rule`/`l3`）。
- 删 `would_llm`、`reactPromptId` 展示；`plan.params` 若含 `skillId`/`agentIds` 展示绑定。

### 4.4 `PromptPrinciplesPanel` 意图节收敛

- `ROUTING_STEPS` 改：`模式（用户锁定 fast/pro/workflow）→ 轨道（A：skill+agent / B：仅 workflow）→ L0 硬绑定 → 统一规则 → L3 补绑定 → 分发（ReactExecutor / PlannerHarness / WorkflowExecutor）`。
- `FORCE_ROWS` 删 `react`/`plan-workflow` 措辞（触发 = 底栏三模式；效果 = 锁定执行模式不再自动改道；仍走 = L0 / 同 mode 规则 / L3）。
- 上下文分层（MESSAGE_STACK / BUDGET / AGENT_ROWS）**保留不动**。

## 5. 验收

| 用例 | 预期 |
|------|------|
| pro + `/skill` | intent 步显示「已完成意图识别」；抽屉有 `处理方式=按您选择的「专业」模式处理`、`匹配方式=自动匹配技能与助手`、`绑定技能/执行方案=使用技能「{displayName}」处理` |
| fast + 规则命中 | intent 步显示「已完成意图识别」；抽屉 `智能匹配=命中常用处理规则`、`执行方案=交由智能体自主分析作答` |
| workflow + `#` | intent 步显示「已完成意图识别」；抽屉 `匹配方式=直接按流程模板执行`、`绑定流程=使用流程「{displayName}」`、`执行方案=将执行「{displayName}」流程`；试跑面板显示 `工作流 · knowledge-qa` |
| 试跑面板 | 无 `plan-workflow`/`react`/`would_llm` 残留文案 |
| 老消息 | 无 `routingTraces` 时抽屉不渲染路由区块 |
| 单测 | `ForcedExecutionRouter` trace 文案断言；serde 写出/读回；前端 `RoutingDryRun` 标签测试 |

## 6. 不做（后续）

- 阶段 D / R-4：删 PlanWorkflow 源码（届时连 `plan-workflow` 前端文案一并清）
- 方案 C 可视化路由树
- `intent.classifier` live bump（L3 trace 已有内容可展，但 live bump 是另一计划）
