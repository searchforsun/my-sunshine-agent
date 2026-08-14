# Planner-Executor 架构重建 — 取代动态 Plan-Workflow

> **状态**：**内核 H-0～H-4 + 过渡入口 ✅** · **H-5 ✅**（[routing v6+H-5 plan](../plans/2026-08-13-unified-routing-v6-h5.md)）· **H-6 ✅**（[planner-h6-frontend](../plans/2026-08-13-planner-h6-frontend.md)）· **H-7 代码 ✅ / Live 待部署跑**（[planner-h7-live](../plans/2026-08-13-planner-h7-live.md)）· **阶段 D 源码删除 ✅**（Live 回归随 H-7 部署后跑）· **4.14 唯一 SSOT**（原 [planner-harness-loop v8](./archive/2026-07-31-planner-harness-loop-design.md) 已归档，废案勿再改）
> **v2（2026-08-05）**：简化决议 S1–S7（§0.1）。**v3（2026-08-07）**：§3.1.1 上下文契约。**v4（2026-08-10）**：S5 单一循环（无 full/hier）。**v5（2026-08-10）**：§4 分层普通时间线 + TaskBoard。**v6（2026-08-10）**：归档 harness；§5.0 PlanNotebook 定稿模型 + §5.4 S6 重规划表迁入本文。
> **v7（2026-08-13）**：勘误 D6/`PlanValidator`/Redis key/TTL/GoalAlignment/`CollapsibleConfirmPanel`；**长负载预算**上调（§5.0 / §8.1，对齐现网 `react.task-max-iters` / spawn·exec-wall）。
> **v8（2026-08-13）**：命名对齐 routing **四轴**——会话形态 `kind`（废 `scene`）；执行模式 `executionMode`；业务域 `biz_scene`（暂不动）；LLM 调用点 `callSite` / DB·MQ `call_site`（废 `call_scene`）。正文若仍写 `scene=chat|task` 均读作 `kind`。
> **v9（2026-08-13）**：§7 落地进度——H-0～H-4 + 过渡入口 ✅。
> **v10（2026-08-13）**：H-5 ✅（routing v6：`fast`/`pro`/`workflow` + ResourceDispatcher；`pro`→harness）。
> **v11（2026-08-13）**：H-6 ✅（分层时间线 + Composer UX；TaskBoard H1 待 harness `tasks` SSE）；H-7 全量 Live / 阶段 D（R-4）**未做**。
> **v12（2026-08-13）**：对照代码冻结 H-7 缺口；实施计划 [planner-h7-live](../plans/2026-08-13-planner-h7-live.md)。
> **v13（2026-08-13）**：H-7 **代码**落地（`tasks` SSE / handoff / `planner-answer` / follow-up obsolete / `plan.worker_*` / `verify_planner_executor_live.py`）；单测绿；**全量 Live 需部署 orchestrator 后跑脚本**；阶段 D 仍后置。
> **v14（2026-08-14）**：阶段 D 核对完成——`WorkflowPlanner`/`PlanWorkflowExecutor`/`PlanApproval*`/`PLAN_WORKFLOW` 路由入口源码**零残留**；`PlanMaterializer`/`PlanNormalizer`/`PlanTimeline`/`PendingInteraction`/`ResumeInteractionHint` 经代码核对为**静态 Workflow / HITL / Recovery 复用**，从舍弃表改列保留（§2.1）；routing spec R-4 同步 ✅。
> **日期**：2026-08-05
> **编号**：阶段四增量（重建 Planner-Executor，删除动态 Plan-Workflow）
> **前置**：
>   - [统一资源路由 v6](./2026-07-29-unified-routing-design.md) — 用户三模式 `fast`/`pro`/`workflow` + 双轨意图收集 + `ResourceDispatcher`（**H-5 ✅**；R-4 = 阶段 D **✅**）；**命名四轴 SSOT**
>   - [ReAct 目标对齐 4.7.7](./2026-07-27-react-goal-alignment-design.md) — Middleware/完整 4.7.7 **仍设计态**；harness 内已有**机械薄实现** `plan.harness.GoalAlignmentValidator`（DEVIATED/STUCK，H-3）
>   - [多 Agent 统一设计](./2026-07-29-multi-agent-unified-design.md) — spawn_subagent 中心化编排 + `AgentRunRequest`
> **依赖与落地顺序（跨 spec）**：[specs/README.md §活跃增量方案](./README.md#活跃增量方案依赖与落地顺序2026-08-13) — 主链 `H-0～H-6 ✅ → H-7（代码 ✅ / Live 待部署）→ 阶段 D ✅`。
> **现状基线（2026-08-14）**：主路径已是 `fast|pro|workflow`；`pro`→`PlannerHarnessExecutor`（`harness.enabled=false` 时 pro **显式失败**）。阶段 D 源码删除**已完成**：`WorkflowPlanner`/`PlanWorkflowExecutor`/`PlanApproval*`/`PLAN_WORKFLOW` 零残留；`PlanMaterializer`/`PlanNormalizer`/`PlanTimeline`/`PendingInteraction`/`ResumeInteractionHint` 因**静态 Workflow / HITL / Recovery 复用**改列保留（§2.1）。
> **命名**：① 本文 `PlannerHarness*` / `harness.*` 与 AgentScope 官方 `HarnessAgent`（Compaction 载体）**无关**；② 四轴见上表，**禁止**用 `scene`/`call_scene` 承载会话形态或调用点。
> **一句话**：**完全舍弃动态 Plan-Workflow**（Planner 一次性 DAG 规划 + PlanApproval 用户确认 + Plan DAG 时间线），重建为真正的 Planner-Executor——Planner 是 ReAct 主 Agent（全量上下文 + PlanNotebook 叠加），Worker 是 Planner 的**工具调用**（`forWorker()` 丰富上下文），**单一循环**边规划边执行（信息不足先调研再重规划；细则在 Worker ReAct，见 §0.1 S5）。**静态 Workflow（4.13 Studio 编排）保留**；新 Planner-Executor 正文用**分层普通时间线** + TaskBoard 一/二级待办（见 §4），**不**渲染 Plan DAG、**不**用步骤卡片形态。

| 轴 | 新名 | 废弃 |
|----|------|------|
| 会话形态 | `kind`（`chat`/`task`） | `scene` |
| 执行模式 | `executionMode` | — |
| 业务域 | `biz_scene`（暂不动） | — |
| LLM 调用点 | `callSite` / DB·MQ `call_site` | `call_scene` |

---

## 0. 架构决策记录（ADR 摘要）

| # | 决策 | 说明 |
|---|------|------|
| D1 | **完全舍弃动态 Plan-Workflow** | `WorkflowPlanner`（一次性 DAG 生成）+ `PlanWorkflowExecutor` + `PlanApprovalService` + Plan DAG 时间线全部删除 |
| D2 | **静态 Workflow 保留** | 4.13 Studio 编排的确定性业务流（`WorkflowExecutor` + `StaticPlanAdapter`）是已验收资产，与 LLM 动态规划正交 |
| D3 | **DAG 画布留存给静态 Workflow** | `PlanExecutionCanvas` / `PlanDagExpandLayer` / `usePlanDagExpand` 保留，仅服务静态 Workflow |
| D4 | **新 Planner-Executor 用分层普通时间线 + TaskBoard** | 正文复用 ReAct 式 `OperationStack` 时间线（层级折叠，**非**卡片墙）；看板一级=H1、二级=Worker todolist；不渲染 Plan DAG |
| D5 | **Plan Approval 完全不做** | 渐进式自驱执行；删 `PlanApprovalService` / `PlanApprovalActions` 及 **PlanApproval 对 `CollapsibleConfirmPanel` 的用法**；**保留**该组件供 HITL/Recovery/Decision 共用壳 |
| D6 | **复用 AgentRuntime 内核** | Planner = `AgentRuntime.run(PLANNER)`（**独立角色**，非 MAIN）；Worker = `AgentRuntime.run(WORKER)`（新角色）；子 Agent = `AgentRuntime.run(SUB)`。现有 `PlannerAgentRuntime`（一次性 `WorkflowPlanner`）须**重写语义**为 ReAct + H1，非零改复用 |
| D7 | **复用审计通道** | `PlanExecutionAuditService` 事件通道复用，新增 `plan.worker_*` 事件 |
| D8 | **终态复用 ExecutionPlanStatus 枚举** | `completed / completed_with_errors / failed / rejected / degraded_react`，不新建状态机 |

---

## 0.1 简化决议（v2 · 2026-08-05）

> 依据：企业级智能中台定位（服务 B/C 端、对话+任务双场景）下对 v8 详设的逐项冗余评估（逐项实证核对见 §11）。**核心原则：Planner 复用 AgentScope 官方能力与既有管线，不重复造轮子**。

| # | 决议 | 说明 | 对应 v8 设计 |
|---|------|------|------------|
| **S1** | **砍独立 Evaluator**（TaskEvaluator / GoalEvaluator 不实现） | Chat/Task 统一 **Planner 自判**（`selfAssess`），省每 task 一次 LLM 调用与 2 个 prompt + `harness_eval_result` 表。真实代价：长语义任务无 Maker-Checker 防确认偏误——由用户反馈 + GoalAlignmentValidator 机械校验兜底 | v8 §4.4 |
| **S2** | **持久化降级 Redis 单写** | `PlanNotebookStore` 仅 Redis save/load/delete/renewTtl；**删** `PlanNotebookMysqlWriter` / `PlannerNotebookEntity` / `PlannerNotebookRepository`、`planner_notebooks` 表 DDL、version 幂等重放。冷审计职责由既有 `PlanExecutionAuditService`（RocketMQ/MySQL/ES）覆盖 | v8 §5.1 |
| **S3** | **去 Tier 0/1/2 形式化分层与压缩点基建（仅 H1）** | run 内压缩由 AgentScope 官方 `CompactionMiddleware`（已落地）负责；跨轮 L1 压缩由既有 `L1Compressor` + `far_folded_msg_ids` + **压缩点模式**（[五层 spec §5.5/§13.3](./2026-07-31-unified-context-compression-design.md)）负责，**保持不动**；H1 仅作为 `injectedBlocks` 固定注入 query 前，rounds 超阈值时简单截断为摘要。**不新建**压缩点 / last_folded_round / 幂等 upsert | v8 §2.3.4/§2.4 |
| **S4** | **砍 P2 `PlanSharedMemoryStore`** | WorkerContextFactory 从 H1 rounds 按 taskId/dependsOn 读已完成 handoff 注入，不建第三份状态 + KV 红线规则 | v8 §2.5.1 |
| **S5** | **取消分解模式枚举 → 单一循环**（v4 定稿；取代「三态→两态」） | **不设** `full` / `hierarchical` / `incremental`、`taskDecomposition`、`completeness`、强制「阶段骨架→阶段细拆」协议、`planner.phase` / `callSite=plan-phase`（旧称 call_scene）。引擎只跑：**Plan（吐调度单元）→ Validate → Execute Workers → selfAssess → replan/done**。信息不足时 Planner 自然排调研步，handoff 后按 S6 重规划；**细则（文件/命令级）在 Worker 内 ReAct**，Planner 只吐可调度粗单元（可并行 / `dependsOn` / 写 H1）。高不确定探索由用户选 **快速 `fast`**（ReAct），**非** harness 内模式分支、**非** L3 自动改道（[routing v6](./2026-07-29-unified-routing-design.md)） | v8 §0.2/§4.1 |
| **S6** | **重规划收敛** | 5 类触发 → **3 类显式**（①连续失败 ③目标变更 ④进度偏差）+ 预算熔断（maxRounds/maxDuration）；②信息缺口由「调研步 + 自然重规划」承接（不再绑阶段切换协议）；⑤资源溢出折叠为熔断。删 plan-similarity 语义去重（`max-replans` 已兜底）；**保留** GoalAlignmentValidator 的 DEVIATED/STUCK | v8 §5.2.2 |
| **S7** | **harness 不复用 PlanValidator** | `PlanValidator` 校验 BPMN/DAG 硬契约（节点 type 白名单、answer 强制、网关拓扑），与 harness 线性 task 队列语义不符。harness 用轻量结构校验（id/label/依赖环）；PlanValidator 留给静态 Workflow | v8 §4.2 |

**保留不变**：会话形态 `kind`（`chat`/`task`，用户显式；与 `executionMode` 正交，§6.1）；PlanNotebook (H1) 跨轮记忆；Planner/Worker 职责分离 + `forWorker()` 丰富上下文 + toolWhitelist 下发；handoff 双写（L1 尾部 + H1）；超时/重试/熔断预算（**初值见 §8.1 长负载档**）；降级通道（Planner 全失败 → React 兜底）；复用 AgentScope StateStore / AgentRuntime / 审计 / 沙箱 / spawn_subagent。  
**GoalAlignment**：harness 包内机械薄实现 ✅（`staleRounds` / 完成度启发式）；完整 [4.7.7](./2026-07-27-react-goal-alignment-design.md) Middleware / ReAct 共用层仍 ⬜。

---

## 1. 背景与问题

### 1.1 现状：动态 Plan-Workflow 是「简易 Workflow 动态版」

当前 `PLAN_WORKFLOW` 执行模式的本质缺陷：

```
用户输入
  → L0/L1/L2 路由命中 PLAN_WORKFLOW
  → WorkflowPlanner（一次性 LLM 调用）生成 PlanJson（nodes + edges DAG）
  → PlanValidator 校验 + Replan（≤2 次）
  → PlanApprovalService 等用户确认
  → PlanMaterializer + PlanNormalizer 物化为可执行 DAG
  → WorkflowExecutor 拓扑调度执行（rag/tool/agent 节点）
  → 静态 DAG 时间线展示（PlanWorkflowPanel + PlanExecutionCanvas）
```

**问题**：
1. **规划一次、执行到底**：Planner 只在开头做一次全量 DAG 规划，执行期不能改图（`NodeRetryExecutor` 只做节点级重试）。长任务的信息缺口无法在规划期预见，DAG 无法自适应。
2. **Planner 不是 Agent**：`WorkflowPlanner` 是**一次性 LLM 调用**（`/chat/completions` 返回 JSON），不是 ReAct 主 Agent。它没有 think→tool→observe 循环，无法在规划中使用工具补信息。
3. **用户确认是约束而非特性**：PlanApproval 阻塞执行等待用户确认，与「Agent 自主推进」的体验矛盾；`approval.on-timeout=fallback_react` 说明确认机制本身是负担。
4. **DAG 时间线展示与执行模型耦合**：前端 `PlanWorkflowPanel` 假设「先全量 DAG，再逐节点执行」，无法表达「边执行边重规划」。
5. **维护成本**：`WorkflowPlanner` + `PlanMaterializer` + `PlanNormalizer` + `PlanApprovalService` + `PlanTimeline` 等 20+ 类专为「一次性 DAG」服务，与真正的 Planner-Executor 需求大部分不匹配。

### 1.2 目标：真正的 Planner-Executor

对齐业界共识（Devin / Claude Code / Cursor Agent Swarm）：

```
Planner（ReAct 主 Agent）—— 只规划、决策、综合，不执行
  │  think → worker-1(工具调用) → observe(handoff) → think → ...
  ▼
Worker（Planner 的工具调用）—— 只执行、不规划，拥有 forWorker() 丰富上下文
  │  内部 ReAct 循环 + 可 spawn 真正隔离的子 Agent
  ▼
handoff 双写：H1 PlanNotebook + Planner L1 尾部（视同 tool_result）
  ▼
Planner 自判（selfAssess，S1 统一，无独立 Evaluator）→ Planner 决策 → 综合回答
```

核心差异 vs 旧 Plan-Workflow：

| 维度 | 动态 Plan-Workflow（删除） | Planner-Executor（新建） |
|------|--------------------------|--------------------------|
| 规划 | 一次性全量 DAG（`WorkflowPlanner`） | **单一循环**边走边规划（`HarnessPlanner` = ReAct；信息不足先调研再重规划） |
| Planner 本质 | 一次性 LLM 调用 | ReAct 主 Agent（全量上下文 + H1） |
| 执行 | DAG 物化 + 拓扑调度（`WorkflowExecutor`） | Worker = 工具调用（`forWorker()`） |
| 重规划 | 校验失败 Replan（≤2 次），执行期不能改图 | **3 类显式触发**式重规划（失败重试耗尽 / 目标变更 / 进度偏差）+ 预算熔断 |
| 用户交互 | PlanApproval 强制确认 | 渐进式自驱，follow-up 重定向 |
| 时间线 | Plan DAG 画布 | 分层普通时间线 + TaskBoard（§4） |
| 持久化 | `execution_plan` 表（plan-workflow 部分） | **PlanNotebook Redis 单写**（会话级跨轮记忆） |

---

## 2. 资产处置清单

### 2.1 保留（复用，零改动或小改）

| 资产 | 用途 |
|------|------|
| `AgentRuntime.run` / `ReActAgentRuntime` / `PlannerAgentRuntime` | 统一执行内核；Planner = **独立 `AgentRole.PLANNER`**（**重写**现有一次性规划实现 → ReAct + H1；非 MAIN、非零改） |
| `ReactExecutor` | 用户选 `fast` 时的普惠层 |
| `WorkflowExecutor` + `StaticPlanAdapter` + `WorkflowCheckpoint` | **静态 Workflow**（4.13 确定性流程） |
| `PlanValidator` + `PlanExecutionSchedule` | **仅静态 Workflow**（S7：harness **不**复用 PlanValidator；用轻量 id/label/依赖环校验） |
| `NodeRetryExecutor` + `NodeRetryPolicyResolver` | 重试语义，抽象出「S 域任务级重试」接口供 `taskRetryMax` 复用 |
| `PlanExecutionAuditService` | 审计通道，新增 `plan.worker_*` 事件 |
| `ExecutionPlanStore` / `ExecutionPlanRepository` | **仅静态 Workflow** 使用（`StaticPlanAdapter` 快照） |
| 工具链全链路 | `CatalogRemoteAgentTool` / `RagTool` / 沙箱 / `spawn_subagent` |
| 前端 `PlanExecutionCanvas` / `PlanDagExpandLayer` / `usePlanDagExpand` | **仅服务静态 Workflow**（D3） |
| `OperationStack` / `TaskBoardPanel` / `SubStepsFold` | 普通时间线 + 看板；harness 复用并做层级扩展 |
| `CollapsibleConfirmPanel` | **保留** HITL/Recovery 共用壳；仅断开 PlanApproval 绑定（D5） |
| `PlanMaterializer` / `PlanNormalizer` | **静态 Workflow** 快照解析/规范化（`WorkflowDefinitionLoader` / `WorkflowResumeService` / `ExecutionPlanStore` / `PlanAnswerPromptAssembler`）；阶段 D 核对后保留 |
| `PlanTimeline` | **静态 Workflow / harness** 的 plan 步骤与 fallback 工具（`WorkflowStaticPlanRunner` / `PlannerHarnessExecutor`）；阶段 D 核对后保留 |
| `PendingInteraction` / `ResumeInteractionHint` | **HITL / Recovery** 等待态复用（`HitlConfirmationService` / `WorkflowNodeRecoveryService` / `WorkflowNodeRunner`）；仅断开 plan 确认绑定，阶段 D 核对后保留 |

### 2.2 舍弃（随动态 Plan-Workflow 一并删除）

| 资产 | 说明 |
|------|------|
| `ExecutionMode.PLAN_WORKFLOW` 路由入口 | 语义路由不再产生 PLAN_WORKFLOW（**已删**：`ExecutionMode` 收敛为 `FAST/PRO/WORKFLOW`） |
| `WorkflowPlanner` | 一次性 DAG 生成 LLM 调用（**已删**） |
| `PlanWorkflowExecutor` / `PlanWorkflowPlanningRunner` / `PlanWorkflowResumeRunner` | 动态 DAG 编排（**已删**） |
| `PlanApprovalService` / `PlanApprovalUserAction` / `PlanApprovalDecision` / `PlanApprovalRound` / `PlanApprovalWaitResult` / `PlanApprovalRejectedException` | 确认机制（D5；**已删**） |
| `WorkflowPlanner` 的 `planner.prompt` Catalog | 一次性规划 prompt |
| golden set §A（PLAN_WORKFLOW 用例） | 迁移到 harness/ReAct 语义 |
| 前端 `PlanApprovalActions` + PlanApproval→`CollapsibleConfirmPanel` 绑定 | 确认 UI（D5；**不**删共享 Confirm 壳） |
| 前端 `/plans/:planId` 页（plan-workflow 专属部分） | 动态 plan 详情 |
| `execution_plan` 表中 plan-workflow 生成的行 | 静态 Workflow 快照仍使用该表 |
| `PlanNotebookMysqlWriter` / `PlannerNotebookEntity` / `PlannerNotebookRepository` / `planner_notebooks` DDL | **S2：持久化降级 Redis 单写** |
| `PlanNotebookRecoveryService` | **S2：恢复复用 AgentScope StateStore 既有 checkpoint** |
| `GoalEvaluator` / `TaskEvaluator` / `harness_eval_result` | **S1：统一 Planner 自判** |
| `PlanSharedMemoryStore` (P2) | **S4：从 H1 rounds 读上游 handoff** |

### 2.3 新增（H-0～H-6 ✅；H-7 代码 ✅ / Live 待部署；阶段 D 源码删除 ✅）

| 组件 | 用途 |
|------|------|
| `PlannerHarnessExecutor` | ResourceDispatcher 入口；`executionMode=pro` 进入；记忆闸门按 `kind`（chat/task） |
| `PlannerHarnessLoop` | 单一循环编排引擎（Plan→Execute→Assess + 超时/重试/Stuck） |
| `HarnessPlanner` | 按现有信息吐调度单元 + 3 类触发式重规划 + selfAssess + 综合回答（**无**分解模式自判） |
| `PlanNotebook` (H1) | 跨轮共享工作记忆 POJO |
| `PlanNotebookStore` | **Redis 单写**（save/load/delete/renewTtl） |
| `WorkerContextFactory` | `AssembledContext.forWorker()` 构造，从 H1 rounds 读上游 handoff |
| `GoalAlignmentValidator` | 目标对齐校验（DEVIATED/STUCK，机械） |
| `AgentRole.WORKER` + `AgentRunRequest.worker()` + `AssembledContext.forWorker()` | Worker 角色 |
| harness 分层时间线层级 + TaskBoard 一/二级投影 | 见 §4（v5） |

> **S1/S4 裁撤**（相对 v8）：独立 `GoalEvaluator` / `TaskEvaluator` / `PlanSharedMemoryStore` / `harness_eval_result` **不实现**；`PlanNotebookRecoveryService` **不实现**（恢复 = Redis load + AgentScope StateStore 既有 checkpoint 续跑）。

---

## 3. 架构总览

### 3.1 三层 Agent 角色

| 层级 | 角色 | 上下文 | 能力 |
|---|---|---|---|
| L0 | Planner = ReAct 主 Agent（**`AgentRole.PLANNER` 独立运行态**，`PlannerAgentRuntime` 实现；全量 ReAct 能力 + H1 PlanNotebook） | 全量：L1 + L2 + H1 PlanNotebook（稳定前缀在前、H1 固定 query 前，S3）；L1 组装**与普通 ReAct MAIN 完全一致**（v3 决策：复用 `ContextAssembler.assemble`，chat 含 L3 召回） | 规划、调度 Worker、自判决策、综合回答 |
| L1 | Worker = Planner 的**工具调用**（`AgentRole.WORKER`） | `forWorker()`：稳定前缀 + taskGoal + constraints + toolWhitelist + query（v3 决策：**不注入 L2 用户画像**） | ReAct 自主循环、内部 spawn 子 Agent |
| L2 | 子 Agent（`AgentRole.SUB`） | `forSubAgent()=empty()`：仅 spawn prompt → 输出 | 单次执行，最严格隔离 |

### 3.1.1 Planner/Worker 上下文契约（v3 定稿 · 2026-08-07）

> 对齐 [压缩点模式（五层 spec §5.5）](./2026-07-31-unified-context-compression-design.md) 与 task/chat Near 差异（v14/v15）。核心结论：**只有 Planner 带跨轮压缩点包袱，Worker/子 Agent 不用**。

| 角色 | 上下文构成 | 压缩处理 | 生命周期 |
|------|-----------|----------|----------|
| **Planner** | `ContextAssembler.assemble(chat_message 历史)`（L2 + Far + Mid + Near + L3 + guide，按 `kind` 走 v14/v15 Near 规则）**+ H1 注入块（query 前 injectedBlock）** + Worker handoff（run 内，视同 `tool_result` 追加 L1 尾部） | 跨轮：既有 `L1Compressor` + `far_folded_msg_ids` 压缩点模式（§5.5.4①，Near 只增、80%/40 轮触发前移一次）；run 内：AgentScope `CompactionMiddleware`（handoff 大结果先 `ToolResultEviction`） | 会话级（多轮 run 共享 L1 + H1） |
| **Worker** | `forWorker()`：**稳定前缀**（tools 白名单 + `harness.worker` 模板 + taskGoal/constraints/expectedOutput/successCriteria + P0 项目规范/W0 只读子集，同一 plan run 内字节不变）+ **动态段**（upstreamResults 按 `dependsOn` 定向 + query） | **不做 L1 压缩点**（单任务用完即毁）；内部 ReAct 循环用 AgentScope `CompactionMiddleware` + `ToolResultEviction`（S 域有界，§2.5.5） | 单任务，结束即销毁 |
| **子 Agent** | `forSubAgent()=empty()`：仅 spawn prompt（任务描述 + 输入） | 无（最严格隔离） | 单次执行 |

**三条注入红线（KV 缓存）：**
1. Worker handoff **不落 `chat_message`**，只进 H1 + run 内 L1 尾部；跨轮 Planner 新 run 的 L1 历史 = 普通 user/assistant 对话（`loadHistory`），Worker 结果认知靠 H1 重建
2. Worker 的 `upstreamResults` 只渲染 **动态段（query 附近）**，禁止写入稳定前缀——否则每个 Worker 前缀字节不同，跨 worker 前缀复用全失效（v8 §2.5.3 规则 6）
3. H1 注入块固定 `query 前`（= `PromptComposer.appendReactInjectedContexts` 现有注入点），**零新增机制**；Worker handoff 在 Planner L1 天然 tail append，不重排 Near/Mid（v8 §2.3.3）


### 3.2 执行流程

```
用户选择 executionMode=pro + kind + RoutingResult（轨 A：agentIds/skillIds）
  → PlannerHarnessExecutor
  → PlannerHarnessLoop.start()
      → S1 Plan: HarnessPlanner 按现有信息输出下一组调度单元（写 H1 taskQueue）
            · 信息不足 → 单元可为「调研/摸底」类 Worker
            · 信息够了 → 单元为可执行粗步骤（可并行 / dependsOn）
            · 细则（文件/命令级）不在此展开，留给 Worker 内 ReAct
      → S2 Validate: 轻量结构校验（id/label/依赖环）+ GoalAlignmentValidator（DEVIATED/STUCK，S7）
      → S3 Execute: Worker 工具调用（forWorker），handoff 双写 H1 + Planner L1 尾部
      → S4 决策: Planner 自判（selfAssess，S1，无独立 Evaluator）
      → done? YES → Planner 综合回答 / NO → 3 类触发重规划（S6）→ 下一轮
```

> **高不确定开放探索**：用户显式选 **快速 `fast`** → ReactExecutor（含 spawn/taskboard/沙箱）；**不**在 harness 内再设模式分支，**不**由 L3 自动改道（S5 + routing v6）。

### 3.3 单一循环与职责边界（S5 v4）

**引擎不设分解模式。** 无 `taskDecomposition` / `completeness` / full|hierarchical 自判；无强制「阶段骨架 → 阶段细拆」二次协议。

| 角色 | 吐什么 | 不吐什么 |
|------|--------|----------|
| **Planner** | 可调度粗单元（里程碑/调研/执行步）+ `dependsOn` + 约束/成功标准；写入 H1 | 文件级/命令级细则；full/hier 模式标签 |
| **Worker** | 单元内 ReAct：工具选择、试错、细则展开；handoff 摘要回传 | 全局重规划（那是 Planner 的事） |

自然过程（Catalog 引导，非引擎枚举）：
1. 首轮按已知信息排大致步骤；缺口先排调研 Worker  
2. handoff 暴露新事实 → S6 重规划更新 taskQueue  
3. 微观分解始终在 Worker 内发生  

Planner LLM 调用统一 `callSite=plan`（强弱模型若需分层，走 phase5 5.3，**不**绑分解模式）。**不建** `planner.phase` / `callSite=plan-phase`。

---

## 4. 前端约定（分层普通时间线 + TaskBoard，D4 · v5）

> **原则**：看板管「待办结构与进度」；正文时间线管「执行过程与 handoff」。二者职责分离，互不收束对方的数据。

### 4.1 正文时间线（普通时间线，非卡片）

形态对齐现有 ReAct `OperationStack`：**行式步骤时间线**，按层级缩进/折叠，**禁止**改成步骤卡片墙 / `WorkerCard` 形态。

```
intent
└─ plan(R1)
   ├─ worker-{taskA}                    ← 一级调度单元对应的执行行
   │  ├─ think / tool-* / …             ← Worker 内过程（subSteps 层级）
   │  └─ handoff（摘要行）              ← 仅正文时间线展示与收束
   ├─ worker-{taskB}   （同波可并行推进）
   │  └─ …
   └─ plan(R2)?                         ← 重规划再出一行
└─ planner-answer
```

| 约定 | 说明 |
|------|------|
| 层级 | L0：`intent` / `plan(Rn)` / `planner-answer`；L1：`worker-{runId|taskId}`；L2：Worker 内 think/tool/spawn（`SubStepsFold`） |
| 并行/串行 | 同波（无互相 `dependsOn`）的 worker 行可并行推进展示；有依赖则按波次串行出现——**时间线表达执行序**，不画 DAG |
| handoff | **只在正文时间线**以 worker 子行/收束摘要出现，并双写 H1 + Planner L1 尾部（引擎侧不变） |
| 不做 | Plan DAG 画布、步骤卡片列表、`{mode}` 标签、把二级 todolist 折叠进 handoff |

### 4.2 TaskBoard（一级 / 二级待办）

复用 `TaskBoardPanel` 软清单体验（D11：禁止 mini-DAG / edges / 工具绑定字段）。

| 层级 | SSOT | 展示规则 |
|------|------|----------|
| **一级** | H1 `taskQueue` 投影（plan / replan / worker 状态变更时引擎刷新） | 调度单元 checklist；可按 `dependsOn` 做**波次并行样式**（分组/并排，**不画依赖边**） |
| **二级** | Worker 内 todolist（`todo_write` / `manage_tasks` 等，有则挂在对应一级下） | **有 items 才展示，没有就不渲染该二级区域**；Worker 结束**不**把二级板收束进 handoff，板面状态原样保留（完成/取消等由 Worker 工具自身更新） |

- 一级板 **不是** 要求 Planner 再调一次 `manage_tasks`（避免与 H1 双写漂移）；二级板仍是 Worker 模型自愿维护的软规划。
- handoff 文案/摘要 **禁止**替代或清空二级 todolist。

### 4.3 步骤契约（正文）

| 步骤 | 来源 | 说明 |
|------|------|------|
| `intent` | 路由层 | 同现约 |
| `plan(R{n})` | HarnessPlanner | 本轮规划意图（时间线一行，可展开调度单元摘要） |
| `worker-{id}` | Worker 工具调用 | 时间线一级执行行；内层 `subSteps`；结束时 **handoff 行**（正文收束） |
| `think` | Planner | 轮次间反思（重规划决策） |
| `planner-answer` | Planner | 流式综合回答（正文，非看板） |
| `tasks`（看板） | H1 投影 + 可选 Worker todolist | 与 ReAct `tasks` 步同组件族；harness 下承载一/二级结构 |

### 4.4 前端组件

- **复用**：`OperationStack`（普通时间线骨架）/ `TaskBoardPanel` / `SubStepsFold`
- **扩展**：OperationStack harness 层级（plan 下挂 worker 行；worker 下挂过程 + handoff）；TaskBoard 一级波次并行样式 + 一级下嵌套二级 todolist（条件渲染）
- **移除**：`PlanWorkflowPanel` 动态 plan 分支、`PlanApprovalActions` 及 PlanApproval 对 Confirm 壳的绑定；**不新增** Worker 步骤卡片组件；**保留** `CollapsibleConfirmPanel`（HITL/Recovery）
- **与 4.7.9 DecisionCard**：与 PlanApproval 解耦无关——DecisionCard 为 D16 **自建容器**；Planner 注册/续跑见 [D12](./2026-08-12-react-request-decision-planner-d12.md)
- **静态 Workflow 不受影响**：继续用 `PlanExecutionCanvas` 渲染 DAG（D3）

---


## 5. 持久化与故障转移（S2/S3 简化）

### 5.0 PlanNotebook（H1）定稿模型

> 自归档 harness §2.2 迁入并按 S1/S5 清洗：**无** `taskDecomposition` / `Phase` / `completeness` / Evaluator 字段；`TaskItem` **不**嵌套 `PlanJson` DAG。

```java
public class PlanNotebook {
    private final String originalGoal;
    private final String userQuery;
    private String kind;                          // chat | task（会话形态；废字段名 scene）
    private final Deque<TaskItem> taskQueue;      // 可调度粗单元
    private final List<RoundRecord> rounds;
    private double goalCompletion;                // Planner selfAssess
    private String nextDirection;
    private final Instant createdAt;
    private int maxRounds = 12;                   // 默认对齐 §8.1 长负载档（Nacos 可覆盖）
    private int maxTotalTasks = 24;
    private int currentRound;
    private int totalTasksCompleted;
    private int staleRounds;
    private int replanCount;                      // ≤ max-replans（S6；默认 6）
    // 禁止字段：taskDecomposition / phases / currentPhaseIndex / evaluatorReason
}

public record TaskItem(
    String taskId, String label, String status,   // pending|in_progress|done|fail|obsolete
    List<String> dependsOn,
    String constraints, String expectedOutput, String successCriteria) {}

public record RoundRecord(
    int roundIndex, TaskItem task,
    List<NodeResult> nodeResults,
    double roundGoalCompletion, String assessReason) {}

public record NodeResult(String nodeId, String status, String summary) {}
```

- **注入**：`renderForPlanner()` / 注入块 = 当前计划摘要（goal + taskQueue 状态）+ 近 `near-keep-rounds` 轮 rounds 原文；超阈折叠最老轮为摘要（§3.1.1 / §8.1）
- **一级 TaskBoard**：投影 `taskQueue`（§4.2）

### 5.1 PlanNotebookStore（Redis 单写）

`sunshine:plan:notebook:{sessionId}` → PlanNotebook JSON，TTL **7d**（`redis-ttl-seconds=604800`；Chat/Task 统一；对齐 StateStore / [orchestrator-stateless §3.4](./2026-08-03-orchestrator-stateless-design.md)）。**仅 Redis**：

```java
public interface PlanNotebookStore {
    void save(PlanNotebook notebook);              // 覆盖写，每轮结束 save 一次
    Optional<PlanNotebook> load(String sessionId);
    void delete(String sessionId);
    void renewTtl(String sessionId);
}
```

- **不写 MySQL**（冷审计由既有 `PlanExecutionAuditService` → RocketMQ/MySQL/ES 覆盖，S2）
- **无 version 幂等重放**（单写无竞态，S2）
- **无 C1-C4 多级 checkpoint**（每轮结束整体 save 一次 = 原 C4 粒度，S2）

### 5.2 恢复与自愈（复用既有能力）

| 故障 | 恢复 |
|------|------|
| orchestrator 重启 | Redis load PlanNotebook → 未开始/已完成 task 按状态继续；**IN_PROGRESS 一律标记 FAIL → 下轮 replan 重跑**（task 幂等无害，无需查 Worker 死活） |
| Worker 崩溃/超时 | AgentScope 2.0 官方 `StateStore` 自动恢复 Worker 内部 ReAct 循环（TTL 7d）；Planner 层面仅超时等待（`worker.timeout-ms`）→ FAIL → 重试 → replan |
| Planner LLM 失败 | 重试 maxAttempts → 有结果→综合回答 / 无结果→降级 ReactExecutor |
| Redis 不可用 | 内存模式（仅本次 Loop），Loop 结束一次性写审计通道 |
| Stale ≥ 阈值 | 强制综合回答 |

> **S3 注记（v3/v4 定稿 · H1 两级压缩）**：Planner 的 run 内压缩由 AgentScope 官方 `CompactionMiddleware`（`HarnessAgent.compaction()`）负责；跨轮 L1 压缩由既有 `L1Compressor` + `far_folded_msg_ids` 负责；**H1 仅注入块（query 前），不建压缩点基建**。H1 注入块**内部两级**（见 §3.1.1）——当前计划摘要（goal + taskQueue 状态）+ 近 N 轮原文（`near-keep-rounds`，默认 **10**，v7 长负载）逐轮追加、超阈值时最老轮次 LLM 折叠为摘要（一次折叠只 miss 尾部小块，C2）；折叠语义与 L1 压缩窗口无关（窗口配置见 §8.1 `notebook.compression`）。**无**阶段骨架 / Phase 协议字段。

### 5.3 降级通道（复用，非新建）

```
Worker TIMEOUT → taskRetryMax 次重试 → FAIL → Planner replan
Planner LLM 全失败 → 有结果→综合回答 / 无结果→降级 ReactExecutor
Stale ≥ 阈值 → 强制综合回答
任意阶段 maxRounds 耗尽 → Planner 回答
Redis 不可用 → 内存模式 → Loop 结束写审计
```

> **对齐旧降级通道**：Planner 全失败降级 React 复用 `fallback_react` 语义（`degraded_react` 终态 + partial-context 注入），保持用户侧降级 UX 一致。

### 5.4 触发式重规划（S6）

Executor 监控，命中即交 Planner 重规划（检测可量化，不做二次推理）：

| # | 触发 | 检测 | 响应 |
|---|------|------|------|
| ① | **连续失败** | 单 task 重试耗尽（`taskRetryMax`） | replan 失败单元 |
| ③ | **目标变更** | 用户 follow-up 更新 `originalGoal` | 受影响 task → `obsolete` → replan 剩余 |
| ④ | **进度偏差** | `GoalAlignmentValidator` DEVIATED（若已落地）/ `staleRounds≥stale-rounds-threshold`（默认 **3**）STUCK | 偏离则修正计划；Stuck → 强制综合回答 |
| — | **预算熔断** | `maxRounds` / `max-duration-ms` / `max-replans` | 综合已收集结果；不再开新轮 |

**承接但不单列触发**：信息缺口 → Planner 排调研 Worker + handoff 后自然进下一轮 Plan（S5）；资源溢出 → 折叠进预算熔断。

**边界**：
1. **保留成果**：已 `done` 的 task 幂等跳过，只调未执行部分  
2. **局部修正**：优先改 `taskQueue`，不臆造全局阶段骨架  
3. **上下文隔离**：重规划读 goal + 已完成 handoff（H1），不读 Worker 内部推理  
4. **收敛**：`max-replans`（默认 **6**，v7）；**不**做 plan-similarity 语义去重  
5. **写隔离**：不回滚已完成文件修改（checkout / Git 语义）

---

## 6. 路由接线（对齐 [unified-routing v6](./2026-07-29-unified-routing-design.md)）

> **v6**：用户显式三模式 **快速 `fast` / 专业 `pro` / 工作流 `workflow`**；**取消** L3 自动 `planMode` 识别。专业模式 = 本 spec 的 Planner-Executor；工作流 = 静态 Workflow；动态 Plan-Workflow 删除（D1）。

### 6.1 分发

```
用户选择 executionMode
  ├── fast → ReactExecutor（轨 A：L0–L3 收集 agentIds/skillIds）
  ├── pro  → PlannerHarnessExecutor（轨 A：同上资源包）
  └── workflow → WorkflowExecutor（轨 B：L0–L3 只收集 workflowId；`#` 仅此模式）
```

- **无** `planMode` 字段；**无** `PLAN_WORKFLOW` / `PlanWorkflowExecutor`
- 轨 A **不**收集 workflow；轨 B **不**收集 agent/skill
- 工作流未命中 → 明确失败，**禁止**静默改成 `fast`

### 6.2 golden set 迁移

| 旧用例 | 新语义 |
|--------|--------|
| §A.1 成功路径（PLAN_WORKFLOW） | 用户选 `pro` → PlannerHarnessExecutor |
| §A.2 Replan / 降级 ReAct | harness 校验/Planner 全失败 → 降级 ReactExecutor（终态 `degraded_react`，**不**改用户模式偏好） |
| §A.3 节点重试 | Worker 重试（taskRetryMax） |
| §A.4 关键 tool fail_fast | Worker 失败 → replan → 降级 |
| §A.5 非关键失败 + 残缺 answer | `completed_with_errors` |
| §A.6 fallback_react | `degraded_react` 终态 |
| §A.7 用户确认（Approval） | **删除**（D5） |

---

## 7. 实施阶段（v2 简化后）

### 7.0 落地进度（2026-08-13）

| 阶段 | 状态 | 说明 |
|------|:----:|------|
| **H-0** 基础设施 | ✅ | `PlanNotebook`（字段 `kind`）+ Store 接口 + Nacos `agent.execution.harness` 长负载默认 |
| **H-1** Redis 单写 | ✅ | `PlanNotebookStoreImpl`；键 `sunshine:plan:notebook:{sessionId}` TTL 7d（MySQL Writer 本就未建，S2 无回改债） |
| **H-2** 恢复 | ✅ | load 时 `in_progress`→`fail`；无独立 RecoveryService |
| **H-3** Planner + 校验 | ✅ | `HarnessPlanner` / `TaskQueueValidator` / harness `GoalAlignmentValidator`；`WORKER`+`forWorker`；`WorkerDispatchTool`；Catalog `planner.harness`/`harness.worker`；`PlannerAgentRuntime`→ReAct |
| **H-4** Loop | ✅ | `PlannerHarnessLoop`（预算熔断 + `nextDirection` + Assess 后对齐）；`WorkerContextFactory` |
| **过渡入口**（kernel 附带） | ✅ | 已被 H-5 取代主路径；历史：`harness.enabled`∧`PLAN_WORKFLOW`→harness |
| **H-5** routing v6 三模式 | ✅ | [unified-routing-v6-h5](../plans/2026-08-13-unified-routing-v6-h5.md)：`fast`/`pro`/`workflow` + ResourceDispatcher；`pro`→harness；冒烟 `verify_routing_v6_smoke.py` |
| **H-6** 前端时间线+TaskBoard | ✅ | [planner-h6-frontend](../plans/2026-08-13-planner-h6-frontend.md)：分层时间线 + Composer（task 三模式、分支下移、去 AI 提示）；**follow-up**：TaskBoard 一级 H1 需 harness `tasks` SSE |
| **H-7** Live 全量 | 🟡 | [planner-h7-live](../plans/2026-08-13-planner-h7-live.md)：G1–G6 代码 ✅ + `verify_planner_executor_live.py`；**部署后**跑 P1–P8；现网 Gateway 未起时 Live 未绿 |
| **阶段 D** 删旧 plan-workflow | ✅ | = routing **R-4**；`WorkflowPlanner`/`PlanWorkflowExecutor`/`PlanApproval*`/`PLAN_WORKFLOW` 源码零残留；`PlanMaterializer`/`PlanNormalizer`/`PlanTimeline`/`PendingInteraction`/`ResumeInteractionHint` 经核对为**静态 Workflow/HITL/Recovery 复用**改列保留（§2.1）；全量 Live 回归随 H-7 部署后跑 |

**代码落点**：`orchestrator/.../plan/harness/*` · 灰度 `docs/nacos/sunshine-orchestrator.yaml` → `agent.execution.harness.enabled` · 冒烟 `scripts/verify_planner_harness_kernel_smoke.py`。

### 阶段 H-0：基础设施 ✅

- [x] `PlanNotebook` POJO（goal + taskQueue/`TaskItem` + `RoundRecord`/`NodeResult`；**无** `taskDecomposition` / `Phase` / `completeness`；会话形态字段 **`kind`**）
- [x] `PlanNotebookStore` 接口（Redis 单写四方法）
- [x] Nacos / `AgentExecutionProperties.Harness` 长负载默认（§8.1）
- **出口**：单测绿

### 阶段 H-1：持久化实现 ✅

- [x] `PlanNotebookStoreImpl`（Redis 单写，每轮 save / renewTtl）
- [x] **回改**：无存量 MySQL Writer 可删（S2 从一开始即 Redis-only）
- **出口**：单测（save→load 一致性）

### 阶段 H-2：恢复 ✅

- [x] 恢复 = Redis load + IN_PROGRESS→FAIL→replan 规则（S2），与 Store 单测合并
- **出口**：单测（Redis load + 状态修复）

### 阶段 H-3：HarnessPlanner + 校验 ✅

- [x] `HarnessPlanner`（planNext / selfAssess / synthesizeAnswer；**无**模式自判）
- [x] `GoalAlignmentValidator`（DEVIATED/STUCK，机械薄实现；非完整 4.7.7 Middleware）
- [x] `TaskQueueValidator`（S7，不复用 PlanValidator）
- [x] `AgentRole.WORKER` + `AgentRunRequest.worker()` + `AssembledContext.forWorker()` + `WorkerDispatchTool`
- [x] Catalog 种子 `planner.harness` / `harness.worker`
- **出口**：单测（调度单元输出 + forWorker 上下文构造）

### 阶段 H-4：Loop ✅

- [x] `PlannerHarnessLoop`（Plan→Validate→Execute→Assess；预算 / `nextDirection` / Stuck）
- [x] `WorkerContextFactory`（从 H1 rounds 读上游 handoff，S4）
- **出口**：单测（循环编排 + 故障 / maxReplans 模拟）

### 过渡入口（kernel 附带，非完整 H-5）✅

- [x] `PlannerHarnessExecutor`（load/create notebook → `loop.run` → renewTtl；`fallbackReact`）
- [x] `ExecutionDispatcher`：`harness.enabled` ∧ `PLAN_WORKFLOW` → harness，否则旧 PlanWorkflow
- [x] 内核冒烟脚本（非 §9.2 全量）
- **非本阶段**：`executionMode=pro` / 删旧入口 → 见 H-5

### 阶段 H-5：路由接线 ✅

- [x] `PlannerHarnessExecutor` + `ResourceDispatcher`：`executionMode=pro` → harness（[routing v6](./2026-07-29-unified-routing-design.md)；plan [unified-routing-v6-h5](../plans/2026-08-13-unified-routing-v6-h5.md)）
- [x] 三模式显式选择替代 `auto`/`planMode`；主路径不再调用 `PlanWorkflowExecutor`
- [x] 冒烟：`scripts/verify_routing_v6_smoke.py`（V1/V3/V4/V5）
- **源码删除**（`PlanWorkflow*` 等）→ **R-4 / 阶段 D**（已完成）；`ForcedExecutionRouter` **重写语义保留**，本阶段不断流即可
- **出口**：`pro` 进 harness、`fast`/`workflow` 不进；编译绿 ✅

### 阶段 H-6：前端（分层时间线 + TaskBoard）✅

> 实施计划：[planner-h6-frontend](../plans/2026-08-13-planner-h6-frontend.md) ✅（`f9d9a6e1`…`6de8b35d`）

- [x] OperationStack harness 层级：plan → worker 行 → subSteps + **handoff 行**（普通时间线，非卡片）
- [x] TaskBoard Panel API / 二级 todolist；**一级 H1 完整投影**仍依赖 harness `tasks` SSE（follow-up，不阻塞时间线）
- [x] 断开 `PlanApprovalActions` / PlanApproval→Confirm 绑定（**保留** `CollapsibleConfirmPanel` 供 HITL/Recovery，D5；组件文件阶段 D 已删）
- [x] 静态 Workflow 保留 DAG 展示（D3）；无 `planGraph` 的 harness 走普通时间线
- [x] **Composer UX**：`kind=task` 亦可选 `fast|pro|workflow`；`GitBranchSelector` 在输入框下方；去掉「AI 生成内容仅供参考」提示
- **出口**：视觉验收 ✅

### 阶段 H-7：Live 验收 🟡

> 实施计划：[planner-h7-live](../plans/2026-08-13-planner-h7-live.md)（代码 ✅）

- [x] **验收前置**：H1→`tasks` SSE；worker 步 handoff 文案；`planner-answer` 步；follow-up 目标变更→`obsolete`；薄审计 `plan.worker_*`
- [x] `scripts/verify_planner_executor_live.py`（§9.2 P1–P8；`--suite p1,p3,p4` 最短三角）
- [ ] **全量 Live 绿**：部署 `feature/planner-h7-live` → `python scripts/start.py --restart orchestrator` → 跑脚本
- 回归：静态 Workflow、ReAct、spawn
- **已有**：`verify_planner_harness_kernel_smoke.py`（不替代本阶段）

### 阶段 D（删除）：旧 plan-workflow 代码清理 ✅

- [x] 删除 `WorkflowPlanner` / `PlanWorkflowExecutor` / `PlanApprovalService` 全套（`PlanApprovalUserAction` / `PlanApprovalDecision` / `PlanApprovalRound` / `PlanApprovalWaitResult` / `PlanApprovalRejectedException`）、`ExecutionMode.PLAN_WORKFLOW` 与路由入口
- [x] **核对改列保留**：`PlanMaterializer` / `PlanNormalizer` / `PlanTimeline` / `PendingInteraction` / `ResumeInteractionHint` 经代码核对为**静态 Workflow / HITL / Recovery 复用**，移入 §2.1 保留表，**不删**
- [x] 清理 Catalog `planner.prompt` / `plan-workflow.*`、Nacos `agent.execution.plan-workflow`（grep 零残留）
- [x] 前端删 `/plans/:planId` 动态 plan 专属部分、`PlanApprovalActions` / `PlanWorkflowPanel`（源码零残留；仅测试文件历史文案残留）
- **出口**：grep 零残留 ✅；全量 Live 回归随 H-7 部署后跑

> **实施顺序**：H-0→H-6 ✅ → H-7（代码 ✅，Live 待部署）→ 阶段 D（R-4）✅。阶段 D 是纯减法，不阻塞 harness 灰度；H-7 Live 部署后一并跑全量回归。

---

## 8. 组件与配置

### 8.1 Nacos 新增

> **v7 长负载档（2026-08-13）**：面向多 Worker、可 spawn、含沙箱/工具墙钟的专业模式长任务。对齐现网 `agent.execution.react`：`task-max-iters=100`、`async-tool.spawn-await` 上限约 600s、`exec-wall-timeout-sec=600`、`subagent.timeout-ms=180000`。旧稿 10min 总墙钟 / 2min Worker **过短**，会在真实 coding/调研波次中误熔断。数值为**经验初值**，Live（P1/P2/P7）后可调。

```yaml
agent:
  execution:
    harness:
      enabled: false              # 灰度开关；kernel 冒烟可临时 true（见 §7.0）
      # —— 循环预算（长负载）——
      max-rounds: 12              # Plan→Execute 波次；原 5 偏短
      max-total-tasks: 24         # 含调研/重规划单元；原 10
      max-duration-ms: 14400000   # 单次 harness 墙钟 4h（原 600000=10min）
      stale-rounds-threshold: 3   # STUCK 判定；原 2，长任务略放宽
      task:
        max-retries: 2            # 单 task 失败重试后再 replan；原 1
      planner:
        timeout-ms: 300000        # 单次 Plan/selfAssess/综合 5min（原 60s；Planner=ReAct）
        max-attempts: 3           # LLM 瞬时失败重试；原 2
        max-replans: 6            # S6 收敛上限；原 3
      worker:
        # 须覆盖：Worker 内 ReAct 多轮 + spawn 观察窗（≤~600s）+ sandbox exec-wall（600s）
        timeout-ms: 3600000       # 单 Worker 墙钟 1h（原 120s）
        max-sub-agents: 5         # 原 3；对齐长任务可并行拆分
        # 建议 Worker 内 maxIters 默认取 react.task-max-iters（100），chat 场景可降为 react.max-iters
      notebook:
        redis-ttl-seconds: 604800 # 7d；与 §5.1 / StateStore / sandbox purge 一致（原 86400 与「TTL 7d」矛盾）
        key-prefix: "sunshine:plan:notebook:"   # 完整键 = prefix + sessionId；对齐 orchestrator-stateless
        compression:              # H1 注入块内部两级（§3.1.1）：
          near-keep-rounds: 10    # 近 N 轮原文；原 6；超阈最老轮 LLM 折摘要
                                  # 与 L1 压缩窗口（五层 v14/v15）无关，勿混用
      # 无独立 recovery.*（S2：恢复 = Redis load + IN_PROGRESS→FAIL→replan）
      session:
        idle-timeout-ms: 14400000 # 空闲续跑窗口对齐 max-duration（原 30min 过短）
```

| 参数 | v6 旧值 | v7 长负载 | 对齐依据 |
|------|---------|-----------|----------|
| `max-rounds` | 5 | **12** | 多波调研 + 执行 + 重规划 |
| `max-total-tasks` | 10 | **24** | 粗单元可并行/依赖，含 obsolete |
| `max-duration-ms` | 600000 (10m) | **14400000 (4h)** | 专业模式墙钟；短于则误 `degraded`/强制收束 |
| `stale-rounds-threshold` | 2 | **3** | 长任务进度波动更大 |
| `task.max-retries` | 1 | **2** | 工具抖动后再 replan |
| `planner.timeout-ms` | 60000 | **300000** | ReAct Planner + 综合回答 |
| `planner.max-replans` | 3 | **6** | S6 收敛仍有界 |
| `worker.timeout-ms` | 120000 | **3600000** | ≥ spawn 窗 + exec-wall + 多轮工具 |
| `worker.max-sub-agents` | 3 | **5** | 可拆并行子任务 |
| `notebook.redis-ttl-seconds` | 86400 | **604800** | 与 §5.1「7d」一致 |
| `near-keep-rounds` | 6 | **10** | 长会话 H1 近轮记忆 |
| `session.idle-timeout-ms` | 1800000 | **14400000** | 对齐总墙钟 |
| `recovery.*` | 有 | **删除** | S2 无独立 RecoveryService |

> **S2/S5 裁撤配置**：无 `checkpoint.mysql-*` / `version-gap-alert`（Redis 单写）；无 `evaluator.*`（S1）；无 `plan-similarity-threshold`（S6）；无 `recovery.*`（S2）。  
> **与 react 配置关系**：Worker/子 Agent 的 **iters** 不在 harness 重复发明第二套上限，默认复用 `react.task-max-iters` / `react.subagent.max-iters`；harness 只管 **墙钟与波次预算**。

### 8.2 Catalog 新增

| ID | 用途 |
|----|------|
| `planner.harness` | Planner system prompt（按现有信息排调度单元；信息不足先调研；3 类触发重规划 + selfAssess + 综合回答；含 Worker 工具调用说明；**禁止**要求输出 full/hier 模式） |
| `harness.worker` | Worker system prompt（forWorker 模板；单元内细则展开） |

> **S1/S5 裁撤**：`harness.task-evaluator` / `harness.goal-evaluator` / `planner.phase` **不建**（统一 Planner selfAssess；调用点 `callSite=plan`）。

### 8.3 Catalog 废弃

- `planner.prompt`（一次性规划）→ 由 `planner.harness` 取代
- `plan-workflow.user-modification` / `plan-workflow.replan-feedback` / `plan-workflow.upstream-failure-line` → 删除

---

## 9. 验收标准

### 9.1 单测

| 用例 | 预期 |
|------|------|
| 单一循环无模式字段 | PlanNotebook / plan 步 **无** `taskDecomposition` / full|hier；引擎无模式分支 |
| 信息不足→调研→重规划 | 首轮可只排调研单元；handoff 后 replan 更新 taskQueue，不臆测未知细节 |
| 职责边界 | Planner 调度单元粗粒度；Worker handoff 可含执行结果摘要；二级 todolist 不收束进 handoff |
| TaskBoard 条件展示 | 无二级 items 时不渲染二级区；有则原样展示至会话可见 |
| Worker handoff 双写 | L1 尾部 append + H1 更新 |
| Worker 上下文隔离 | forWorker 含 taskGoal+constraints+toolWhitelist（v3：**不注入 L2**）；内部 think/tool 不回流 |
| H1 两级折叠（v3） | 注入块近 `near-keep-rounds` 轮原文，超阈值最老轮次 LLM 折叠为摘要 |
| Planner L1 组装一致性（v3） | Planner 复用 `ContextAssembler.assemble`（chat 含 L3），与普通 ReAct MAIN 差异仅 H1 注入块 + worker handoff |
| 触发式重规划边界 | 3 类显式触发（S6）；已完成 task 幂等跳过；`max-replans=6`（v7）收敛 |
| 崩溃恢复 | Redis load → IN_PROGRESS→FAIL→replan；task 状态一致 |
| 自判决策 | Planner selfAssess 0~1 分 → 续跑 / replan / 综合回答 |

### 9.2 Live

| # | 场景 | kind | 预期 |
|---|------|:---:|------|
| P1 | 分析 Q2 销售下降 + 改进方案 + 预算 | chat | Planner→Worker→自判→综合；分层普通时间线 + 一级看板；handoff 仅在时间线 |
| P2 | 修复 SQL 注入风险 + 单测 | task | Planner→Worker(内部 spawn)→自判→综合 |
| P3 | 静态 Workflow 回归 | / | `#knowledge-qa` DAG 展示正常（D3 保留） |
| P4 | 简单问答回归 | / | 走 ReactExecutor |
| P5 | 崩溃恢复 | chat | Kill orchestrator → 重启 → 恢复 Notebook → 继续 |
| P6 | 长任务上下文压缩 | chat | 超 `near-keep-rounds`（默认 10）后 H1 最老轮折摘要；配合 AgentScope Compaction + L1Compressor |
| P8 | 长负载预算不误熔断 | task | 单 Worker 含 spawn+沙箱 exec 墙钟接近 600s 时**不**因 `worker.timeout-ms` 误杀；整次 run 在 4h 内可完成多波次 |
| P7 | 信息不足先调研再重规划 | task | 首轮可只排调研 Worker；handoff 后 `plan(R2)`；Worker 有 todolist 则二级板展示，结束板不收束 |

---

## 10. 风险与对策

| 风险 | 对策 |
|------|------|
| 删除旧 plan-workflow 影响静态 Workflow | D3 明确 DAG 画布保留服务静态 Workflow；`WorkflowExecutor` 独立于 `PlanWorkflowExecutor` |
| harness 灰度期无路由入口 | `agent.execution.harness.enabled` 开关 + 直接 API 接入，不依赖 L3 路由即可验证 |
| `AgentRole.WORKER` 破坏现有角色逻辑 | 新增枚举值不改现有 MAIN/SUB/PLANNER 行为；`resolveBridgeId` 加 WORKER 分支 |
| `AssembledContext.forWorker()` 上下文不足 | 稳定前缀（taskGoal + 共享快照 + handoff）+ toolWhitelist + query |
| 终态/审计分叉 | D8：复用 `ExecutionPlanStatus` + `PlanExecutionAuditService` |
| 前端两套时间线并存 | harness 走 OperationStack 分层普通时间线 + TaskBoard；静态 Workflow 走 PlanWorkflowPanel DAG；看板与 handoff 职责分离（§4 v5） |

---

## 11. 关联文档

| 文档 | 关系 |
|------|------|
| [archive/planner-harness-loop v8](./archive/2026-07-31-planner-harness-loop-design.md) | **已归档废案**（三态分解 / Evaluator / MySQL 双写 / H1 压缩点等）；定稿模型见本文 §5.0 / §5.4，勿再改归档稿 |
| [unified-routing-design v6](./2026-07-29-unified-routing-design.md) | 用户三模式 fast/pro/workflow；轨 A/B 意图收集；`pro`→本 Executor；**命名四轴**（`kind` / `executionMode` / `biz_scene` / `callSite`） |
| [orchestrator-stateless](./2026-08-03-orchestrator-stateless-design.md) | Redis 键 `sunshine:plan:notebook:{sessionId}`；Activity 化波次 B2/B3 后置 |
| [react-goal-alignment](./2026-07-27-react-goal-alignment-design.md) | S6④ Validator **前置**（未落地可降级） |
| [specs/README 依赖顺序](./README.md#活跃增量方案依赖与落地顺序2026-08-13) | 跨 spec 落地顺序 SSOT |
| [expert-consultation-design](./archive/2026-07-07-expert-consultation-design.md) | peer-collab（已退役），spawn 中心化替代 |
| [plan-user-approval-design](./archive/2026-06-27-plan-user-approval-design.md) | **被 D5 废弃** |
