# Planner-Executor 架构重建 — 取代动态 Plan-Workflow

> **状态**：📋 设计评审中（2026-08-05 立项）· **v2（2026-08-05 简化决议）**：见 §0.1「简化决议 S1-S7」——砍独立 Evaluator、持久化降级 Redis 单写、去 Tier 分层与压缩点基建、砍 P2 共享内存、三态→两态、重规划收敛、harness 不复用 PlanValidator。
> **v3（2026-08-07 · Planner/Worker 上下文契约）**：新增 §3.1.1 上下文契约定稿——Planner L1 组装与普通 ReAct MAIN 完全一致（复用 `ContextAssembler.assemble`，chat 含 L3）；Worker `forWorker()` **不注入 L2 用户画像**（只含任务契约 + 定向上游 + P0/W0 只读子集）；H1 注入块内部两级（阶段骨架 + 近 `near-keep-rounds` 轮原文，超阈值折叠为摘要，**不建压缩点**）；Worker handoff 不落 `chat_message`（只进 H1 + run 内 L1 尾部）。§8.1 `notebook.compression` 改 `near-keep-rounds`。同步 [harness v8 §2.3.4/§2.4/§4.3](./2026-07-31-planner-harness-loop-design.md) 与 [五层 spec §5.5.7](./2026-07-31-unified-context-compression-design.md) v11 注记。
> **日期**：2026-08-05
> **编号**：阶段四增量（重建 Planner-Executor，删除动态 Plan-Workflow）
> **前置**：
>   - [Planner-Worker Loop v8](./2026-07-31-planner-harness-loop-design.md) — 三态分解（FULL/HIERARCHICAL/INCREMENTAL）+ PlanNotebook + Checkpoint C1-C4 + 触发式重规划（**v8 被本 spec §0.1 简化决议部分覆盖**）
>   - [统一资源路由 v3](./2026-07-29-unified-routing-design.md) — `planMode` + `scene` + `ResourceDispatcher`
>   - [ReAct 目标对齐 4.7.7](./2026-07-27-react-goal-alignment-design.md) — `GoalAlignmentMiddleware` + `FailureBudgetMiddleware`
>   - [多 Agent 统一设计](./2026-07-29-multi-agent-unified-design.md) — spawn_subagent 中心化编排 + `AgentRunRequest`
> **一句话**：**完全舍弃动态 Plan-Workflow**（Planner 一次性 DAG 规划 + PlanApproval 用户确认 + Plan DAG 时间线），重建为真正的 Planner-Executor——Planner 是 ReAct 主 Agent（全量上下文 + PlanNotebook 叠加），Worker 是 Planner 的**工具调用**（`forWorker()` 丰富上下文），**两态分解**（full/hierarchical，见 §0.1 S5）边走边规划。**静态 Workflow（4.13 Studio 编排）保留**，DAG 画布组件留存给静态 Workflow 使用；新 Planner-Executor 采用**步骤时间线卡片**（不渲染 DAG）。

---

## 0. 架构决策记录（ADR 摘要）

| # | 决策 | 说明 |
|---|------|------|
| D1 | **完全舍弃动态 Plan-Workflow** | `WorkflowPlanner`（一次性 DAG 生成）+ `PlanWorkflowExecutor` + `PlanApprovalService` + Plan DAG 时间线全部删除 |
| D2 | **静态 Workflow 保留** | 4.13 Studio 编排的确定性业务流（`WorkflowExecutor` + `StaticPlanAdapter`）是已验收资产，与 LLM 动态规划正交 |
| D3 | **DAG 画布留存给静态 Workflow** | `PlanExecutionCanvas` / `PlanDagExpandLayer` / `usePlanDagExpand` 保留，仅服务静态 Workflow |
| D4 | **新 Planner-Executor 用步骤时间线卡片** | `intent → plan → worker-* → answer` 流式卡片列表，不渲染 DAG |
| D5 | **Plan Approval 完全不做** | 渐进式自驱执行，`PlanApprovalService` / `PlanApprovalActions` / `CollapsibleConfirmPanel` 删除 |
| D6 | **复用 AgentRuntime 内核** | Planner = `AgentRuntime.run(MAIN)`，Worker = `AgentRuntime.run(WORKER)`（新角色），子 Agent = `AgentRuntime.run(SUB)` |
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
| **S5** | **三态→两态** | 删 INCREMENTAL（open 场景走既有 ReAct：已含 spawn/taskboard/沙箱，能力面覆盖）；FULL 并入 HIERARCHICAL（执行机制同为 task 队列 + worker 调用，full 仅「首轮阶段骨架细度=任务粒度」）。删 `completeness` 三态枚举与 `executeIncremental` | v8 §0.2/§4.1 |
| **S6** | **重规划收敛** | 5 类触发 → **3 类显式**（①连续失败 ③目标变更 ④进度偏差）+ 预算熔断（maxRounds/maxDuration）；②信息缺口折叠为 HIERARCHICAL 阶段切换自然产物；⑤资源溢出折叠为熔断。删 plan-similarity 语义去重（`max-phase-replans=3` 已兜底）；**保留** GoalAlignmentValidator 的 DEVIATED/STUCK | v8 §5.2.2 |
| **S7** | **harness 不复用 PlanValidator** | `PlanValidator` 校验 BPMN/DAG 硬契约（节点 type 白名单、answer 强制、网关拓扑），与 harness 线性 task 队列语义不符。harness 用轻量结构校验（id/label/依赖环）；PlanValidator 留给静态 Workflow | v8 §4.2 |

**保留不变**：scene（chat/task）用户显式选择（产品设计，§6.1 保留前端选择器）；PlanNotebook (H1) 跨轮记忆；Planner/Worker 职责分离 + `forWorker()` 丰富上下文 + toolWhitelist 下发；handoff 双写（L1 尾部 + H1）；GoalAlignmentValidator DEVIATED/STUCK；超时/重试/熔断预算；降级通道（Planner 全失败 → React 兜底）；复用 AgentScope StateStore / AgentRuntime / 审计 / 沙箱 / spawn_subagent。

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
4. **DAG 时间线展示与执行模型耦合**：前端 `PlanWorkflowPanel` 假设「先全量 DAG，再逐节点执行」，无法表达「走到哪、规划到哪」的分层增量规划。
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
| 规划 | 一次性全量 DAG（`WorkflowPlanner`） | **两态分解**，边走边规划（`HarnessPlanner` = ReAct MAIN） |
| Planner 本质 | 一次性 LLM 调用 | ReAct 主 Agent（全量上下文 + H1） |
| 执行 | DAG 物化 + 拓扑调度（`WorkflowExecutor`） | Worker = 工具调用（`forWorker()`） |
| 重规划 | 校验失败 Replan（≤2 次），执行期不能改图 | **3 类显式触发**式重规划（失败重试耗尽 / 目标变更 / 进度偏差）+ 预算熔断 |
| 用户交互 | PlanApproval 强制确认 | 渐进式自驱，follow-up 重定向 |
| 时间线 | Plan DAG 画布 | 步骤时间线卡片 |
| 持久化 | `execution_plan` 表（plan-workflow 部分） | **PlanNotebook Redis 单写**（会话级跨轮记忆） |

---

## 2. 资产处置清单

### 2.1 保留（复用，零改动或小改）

| 资产 | 用途 |
|------|------|
| `AgentRuntime.run` / `ReActAgentRuntime` / `PlannerAgentRuntime` | 统一执行内核；Planner = **独立 `AgentRole.PLANNER` 运行态**，由 `PlannerAgentRuntime` 实现（非 MAIN 复用） |
| `ReactExecutor` | planMode=none 路径（普惠层） |
| `WorkflowExecutor` + `StaticPlanAdapter` + `WorkflowCheckpoint` | **静态 Workflow**（4.13 确定性流程） |
| `PlanValidator` + `PlanExecutionSchedule` | 校验引擎，harness 复用 |
| `NodeRetryExecutor` + `NodeRetryPolicyResolver` | 重试语义，抽象出「S 域任务级重试」接口供 `taskRetryMax` 复用 |
| `PlanExecutionAuditService` | 审计通道，新增 `plan.worker_*` 事件 |
| `ExecutionPlanStore` / `ExecutionPlanRepository` | **仅静态 Workflow** 使用（`StaticPlanAdapter` 快照） |
| 工具链全链路 | `CatalogRemoteAgentTool` / `RagTool` / 沙箱 / `spawn_subagent` |
| 前端 `PlanExecutionCanvas` / `PlanDagExpandLayer` / `usePlanDagExpand` | **仅服务静态 Workflow**（D3） |
| `SubStepsFold` / `SubagentCard` / `OperationStack` | 步骤卡片渲染，harness 复用 |

### 2.2 舍弃（随动态 Plan-Workflow 一并删除）

| 资产 | 说明 |
|------|------|
| `ExecutionMode.PLAN_WORKFLOW` 路由入口 | 语义路由不再产生 PLAN_WORKFLOW |
| `WorkflowPlanner` | 一次性 DAG 生成 LLM 调用 |
| `PlanWorkflowExecutor` / `PlanWorkflowPlanningRunner` / `PlanWorkflowResumeRunner` | 动态 DAG 编排 |
| `PlanMaterializer` / `PlanNormalizer` | DAG 物化（仅动态 DAG 需要） |
| `PlanApprovalService` / `PlanApprovalUserAction` / `PlanApprovalDecision` / `PlanApprovalRound` / `PlanApprovalWaitResult` / `PlanApprovalRejectedException` | 确认机制（D5） |
| `PendingInteraction` / `ResumeInteractionHint`（plan 确认部分） | 确认交互状态 |
| `PlanTimeline`（plan DAG 步） | 动态规划 DAG 时间线 |
| `WorkflowPlanner` 的 `planner.prompt` Catalog | 一次性规划 prompt |
| golden set §A（PLAN_WORKFLOW 用例） | 迁移到 harness/ReAct 语义 |
| 前端 `PlanApprovalActions` / `CollapsibleConfirmPanel` | 确认 UI（D5） |
| 前端 `/plans/:planId` 页（plan-workflow 专属部分） | 动态 plan 详情 |
| `execution_plan` 表中 plan-workflow 生成的行 | 静态 Workflow 快照仍使用该表 |
| `PlanNotebookMysqlWriter` / `PlannerNotebookEntity` / `PlannerNotebookRepository` / `planner_notebooks` DDL | **S2：持久化降级 Redis 单写** |
| `PlanNotebookRecoveryService` | **S2：恢复复用 AgentScope StateStore 既有 checkpoint** |
| `GoalEvaluator` / `TaskEvaluator` / `harness_eval_result` | **S1：统一 Planner 自判** |
| `PlanSharedMemoryStore` (P2) | **S4：从 H1 rounds 读上游 handoff** |

### 2.3 新增（harness spec v8 已设计，H-0~H-7 实施）

| 组件 | 用途 |
|------|------|
| `PlannerHarnessExecutor` | ResourceDispatcher 入口，按 scene 区分 Chat/Task 模式 |
| `PlannerHarnessLoop` | 双模编排引擎（S1-S3 + 超时/重试/Stuck） |
| `HarnessPlanner` | **两态分解**自判（full/hierarchical）+ 阶段细拆 + 3 类触发式重规划 + 自判 + 综合回答 |
| `PlanNotebook` (H1) | 跨轮共享工作记忆 POJO |
| `PlanNotebookStore` | **Redis 单写**（save/load/delete/renewTtl） |
| `WorkerContextFactory` | `AssembledContext.forWorker()` 构造，从 H1 rounds 读上游 handoff |
| `GoalAlignmentValidator` | 目标对齐校验（DEVIATED/STUCK，机械） |
| `AgentRole.WORKER` + `AgentRunRequest.worker()` + `AssembledContext.forWorker()` | Worker 角色 |
| 前端 `WorkerCard` / harness 步骤时间线 | 新 Timeline 形态 |

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
| **Planner** | `ContextAssembler.assemble(chat_message 历史)`（L2 + Far + Mid + Near + L3 + guide，按 scene 走 v14/v15 Near 规则）**+ H1 注入块（query 前 injectedBlock）** + Worker handoff（run 内，视同 `tool_result` 追加 L1 尾部） | 跨轮：既有 `L1Compressor` + `far_folded_msg_ids` 压缩点模式（§5.5.4①，Near 只增、80%/40 轮触发前移一次）；run 内：AgentScope `CompactionMiddleware`（handoff 大结果先 `ToolResultEviction`） | 会话级（多轮 run 共享 L1 + H1） |
| **Worker** | `forWorker()`：**稳定前缀**（tools 白名单 + `harness.worker` 模板 + taskGoal/constraints/expectedOutput/successCriteria + P0 项目规范/W0 只读子集，同一 plan run 内字节不变）+ **动态段**（upstreamResults 按 `dependsOn` 定向 + query） | **不做 L1 压缩点**（单任务用完即毁）；内部 ReAct 循环用 AgentScope `CompactionMiddleware` + `ToolResultEviction`（S 域有界，§2.5.5） | 单任务，结束即销毁 |
| **子 Agent** | `forSubAgent()=empty()`：仅 spawn prompt（任务描述 + 输入） | 无（最严格隔离） | 单次执行 |

**三条注入红线（KV 缓存）：**
1. Worker handoff **不落 `chat_message`**，只进 H1 + run 内 L1 尾部；跨轮 Planner 新 run 的 L1 历史 = 普通 user/assistant 对话（`loadHistory`），Worker 结果认知靠 H1 重建
2. Worker 的 `upstreamResults` 只渲染 **动态段（query 附近）**，禁止写入稳定前缀——否则每个 Worker 前缀字节不同，跨 worker 前缀复用全失效（v8 §2.5.3 规则 6）
3. H1 注入块固定 `query 前`（= `PromptComposer.appendReactInjectedContexts` 现有注入点），**零新增机制**；Worker handoff 在 Planner L1 天然 tail append，不重排 Near/Mid（v8 §2.3.3）


### 3.2 执行流程

```
用户输入 + RoutingResult(planMode=harness, scene=chat|task)
  → PlannerHarnessExecutor
  → PlannerHarnessLoop.start()
      → S1 Plan: HarnessPlanner 首轮输出（full / hierarchical 两态自判，S5）
          ├─ hierarchical → 阶段骨架进 H1 → 到达阶段再细拆 task 列表
          └─ full        → 阶段骨架细度 = 任务粒度（同一条执行路径）
      → S2 Validate: 轻量结构校验 + GoalAlignmentValidator（DEVIATED/STUCK，S7）
      → S3 Execute: Worker 工具调用（forWorker），handoff 双写 H1 + Planner L1 尾部
      → S4 决策: Planner 自判（selfAssess，S1，无独立 Evaluator）
      → done? YES → Planner 综合回答 / NO → 3 类触发重规划（S6）→ 下一轮
```

> **INCREMENTAL 场景**（开放/探索任务）：直接走既有 ReAct（`planMode=none`），不进 harness（S5）。

### 3.3 两态分解（S5，替代 v8 三态）

Planner 首轮调用的自然产物决定分解粒度，路由层不感知：
- **hierarchical**（默认复杂任务）：首轮仅 3~5 阶段骨架 → 到达阶段再基于前序真实产出细拆 task 列表
- **full**（信息完备）：首轮阶段骨架细度 = 任务粒度（可视为 hierarchical 的一层特化），执行路径与 hierarchical 相同

模型成本分层：全局粗规划 `call_scene=plan`（强模型，1 次/任务）；阶段细规划 `call_scene=plan-phase`（轻量模型，N 次/任务）。

---

## 4. Timeline 约定（步骤时间线卡片，D4）

### 4.1 主时间线形态

```
intent → plan(R1,{mode}) → worker-1 → worker-2 → ... → planner-answer
```

- Full 模式：`intent → plan(R1,full) → task-1 → task-2 → ... → planner-answer`
- Hierarchical 模式：`intent → plan(R1,hier) → [阶段1细拆] task-* → [阶段2细拆] task-* → ... → planner-answer`
- **不渲染 DAG**：无 `PlanExecutionCanvas` / DAG 展开层；worker 步骤是流式卡片

> **S5 注记**：无 Incremental 模式（open 场景走 ReAct 时间线，不进 harness）。

### 4.2 各步骤约定

| 步骤 | 来源 | 说明 |
|------|------|------|
| `intent` | 路由层 | 同现约 |
| `plan(R{n},{mode})` | HarnessPlanner | 本轮规划意图 + 分解模式（full/hierarchical），卡片式 |
| `worker-{runId}` | Worker 工具调用 | 卡片含 `subSteps`（内部 think/tool 折叠）+ handoff 摘要 |
| `think` | Planner | 轮次间反思（阶段切换/重规划决策） |
| `planner-answer` | Planner | 流式综合回答 |

### 4.3 前端组件

- **复用**：`OperationStack` / `SubagentCard`（worker 卡片复用 subagent-{runId} 折叠机制）/ `SubStepsFold`
- **新增**：worker 步骤卡片（label 取 task label，metadata 含 `taskId` / `dependsOn`）
- **移除**：`PlanWorkflowPanel` 中动态 plan 专属分支、`PlanApprovalActions`、`CollapsibleConfirmPanel`
- **静态 Workflow 不受影响**：继续用 `PlanExecutionCanvas` 渲染 DAG（D3）

---

## 5. 持久化与故障转移（S2/S3 简化）

### 5.1 PlanNotebookStore（Redis 单写）

`planner:notebook:{sessionId}` → PlanNotebook JSON，TTL 7d（Chat/Task 统一取长）。**仅 Redis**：

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

> **S3 注记（v3 定稿 · H1 两级压缩）**：Planner 的 run 内压缩由 AgentScope 官方 `CompactionMiddleware`（`HarnessAgent.compaction()`）负责；跨轮 L1 压缩由既有 `L1Compressor` + `far_folded_msg_ids` 负责；**H1 仅注入块（query 前），不建压缩点基建**。H1 注入块**内部两级**（见 §3.1.1）——阶段骨架 + 近 N 轮原文（`near-keep-rounds`，默认 6）逐轮追加、超阈值时最老轮次 LLM 折叠为摘要（一次折叠只 miss 尾部小块，C2）；折叠语义与 L1 压缩窗口无关（窗口配置见 §8.1 `notebook.compression`）。

### 5.3 降级通道（复用，非新建）

```
Worker TIMEOUT → taskRetryMax 次重试 → FAIL → Planner replan
Planner LLM 全失败 → 有结果→综合回答 / 无结果→降级 ReactExecutor
Stale ≥ 阈值 → 强制综合回答
任意阶段 maxRounds 耗尽 → Planner 回答
Redis 不可用 → 内存模式 → Loop 结束写审计
```

> **对齐旧降级通道**：Planner 全失败降级 React 复用 `fallback_react` 语义（`degraded_react` 终态 + partial-context 注入），保持用户侧降级 UX 一致。

---

## 6. 路由接线

### 6.1 删除 PLAN_WORKFLOW 路由入口

```
RoutingPolicyChain（现状改造后）
  ├── L0 #workflow-id / $agent / @skill（显式绑定）
  ├── L1 规则匹配（workflow 规则 → 静态 Workflow）
  ├── L2 三路语义召回（workflow ≥ 0.88 → 静态 Workflow）
  └── L3 LLM 分类器
      ├── planMode=none → ReactExecutor（通用 ReAct）
      └── planMode=harness → PlannerHarnessExecutor（scene 区分 Chat/Task）
```

- `ExecutionMode` 枚举收敛为 `WORKFLOW / REACT / HARNESS`（或 `ResourceType`）
- 不再产生 `PLAN_WORKFLOW` 语义路由
- 静态 Workflow 仍经 `#workflow-id` / L1 / L2 命中 → `WorkflowExecutor`

### 6.2 golden set 迁移

| 旧用例 | 新语义 |
|--------|--------|
| §A.1 成功路径（PLAN_WORKFLOW） | planMode=harness（Chat 场景） |
| §A.2 Replan / 降级 ReAct | harness 校验失败 → 降级 ReactExecutor |
| §A.3 节点重试 | Worker 重试（taskRetryMax） |
| §A.4 关键 tool fail_fast | Worker 失败 → replan → 降级 |
| §A.5 非关键失败 + 残缺 answer | `completed_with_errors` |
| §A.6 fallback_react | `degraded_react` 终态 |
| §A.7 用户确认（Approval） | **删除**（D5） |

---

## 7. 实施阶段（v2 简化后）

### 阶段 H-0：基础设施

- `PlanNotebook` POJO（含两态字段 + Phase/TaskItem/RoundRecord/NodeResult）
- `PlanNotebookStore` 接口（Redis 单写四方法）
- **出口**：单测绿

### 阶段 H-1：持久化实现

- `PlanNotebookStoreImpl`（Redis 单写，每轮 save）
- **回改**：删除 `PlanNotebookMysqlWriter` / `PlannerNotebookEntity` / `PlannerNotebookRepository` / `planner_notebooks` DDL（S2）
- **出口**：单测（save→load 一致性）

### 阶段 H-2：恢复

- **简化**：不实现独立 `PlanNotebookRecoveryService` 的 StateStore 查询；恢复 = Redis load + IN_PROGRESS→FAIL→replan 规则（S2），与 `PlanNotebookStore` 单测合并覆盖
- **出口**：单测（Redis load + 状态修复）

### 阶段 H-3：HarnessPlanner + 校验

- `HarnessPlanner`（两态自判 + 阶段细拆 + 3 类触发重规划 + selfAssess + 综合回答）
- `GoalAlignmentValidator`（DEVIATED/STUCK，机械）
- 轻量结构校验（S7，不复用 PlanValidator）
- `AgentRole.WORKER` + `AgentRunRequest.worker()` + `AssembledContext.forWorker()`
- **出口**：单测（两态输出 + forWorker 上下文构造）

### 阶段 H-4：Loop

- `PlannerHarnessLoop`（双模编排 + 超时/重试/Stuck）
- `WorkerContextFactory`（从 H1 rounds 读上游 handoff，S4）
- **出口**：单测（双模编排 + 故障模拟）

### 阶段 H-5：路由接线 + 删除旧入口

- `PlannerHarnessExecutor` + `ResourceDispatcher` 迁移
- 删除 `ExecutionMode.PLAN_WORKFLOW` 路由入口、`ForcedExecutionRouter`（随 v3 路由）
- golden set §A 迁移
- **出口**：路由正确 + 编译绿

### 阶段 H-6：前端新 Timeline

- harness 步骤时间线卡片（复用 OperationStack / SubStepsFold）
- 删除 `PlanApprovalActions` / `CollapsibleConfirmPanel` / PlanWorkflowPanel 动态 plan 分支
- 静态 Workflow 保留 DAG 展示（D3）
- **出口**：视觉验收

### 阶段 H-7：Live 验收

- `scripts/verify_planner_executor_live.py`（H1-H13 检查门，v8 §12.2）
- 回归：静态 Workflow（golden set B/C/D/I）、ReAct、spawn

### 阶段 D（删除）：旧 plan-workflow 代码清理

- 删除 `WorkflowPlanner` / `PlanWorkflowExecutor` / `PlanMaterializer` / `PlanNormalizer` / `PlanApprovalService` 全套
- 清理 Catalog `planner.prompt` / `plan-workflow.*`、Nacos `agent.execution.plan-workflow`
- 前端删 `/plans/:planId` 动态 plan 专属部分
- **出口**：grep 零残留 + 全量回归

> **实施顺序**：H-0→H-7 增量建设（harness 可独立工作），**阶段 D 在 H-7 验收通过后执行**。阶段 D 是纯减法，不阻塞 harness 上线。

---

## 8. 组件与配置

### 8.1 Nacos 新增

```yaml
agent:
  execution:
    harness:
      enabled: false            # 灰度开关
      max-rounds: 5
      max-total-tasks: 10
      max-duration-ms: 600000
      stale-rounds-threshold: 2
      task:
        max-retries: 1
      planner:
        timeout-ms: 60000
        max-attempts: 2
        max-phase-replans: 3
        plan-similarity-threshold: 0.8
      evaluator:                # Chat 专用
        timeout-ms: 30000
        goal-threshold: 0.9
      worker:
        timeout-ms: 120000
        max-sub-agents: 3
      notebook:
        redis-ttl-seconds: 86400
        compression:              # H1 PlanNotebook 注入块内部两级（v3 定稿，§3.1.1）：
          near-keep-rounds: 6     #   近 N 轮原文逐轮追加（handoff 摘要 + 阶段骨架），超阈值最老轮次 LLM 折叠为摘要
                                  #   与 L1 压缩窗口（五层 spec v14/v15：chat 4+4+Far / task 2+2+Far≤10k）**无关**，
                                  #   两个 window-size 是不同物同名，勿混用
      checkpoint:
        mysql-async: true
        mysql-retry-max: 3
        version-gap-alert: 3
      recovery:
        timeout-ms: 30000
      session:
        idle-timeout-ms: 1800000
```

### 8.2 Catalog 新增

| ID | 用途 |
|----|------|
| `planner.harness` | Planner system prompt（规划 + 两态分解自判 + 3 类触发重规划 + selfAssess 自判 + 综合回答，含 Worker 工具调用说明） |
| `planner.phase` | 阶段细拆 prompt（HIERARCHICAL） |
| `harness.worker` | Worker system prompt（forWorker 模板） |

> **S1 裁撤**：`harness.task-evaluator` / `harness.goal-evaluator` **不建**（统一 Planner selfAssess，调用点 `call_scene=plan`）。

### 8.3 Catalog 废弃

- `planner.prompt`（一次性规划）→ 由 `planner.harness` 取代
- `plan-workflow.user-modification` / `plan-workflow.replan-feedback` / `plan-workflow.upstream-failure-line` → 删除

---

## 9. 验收标准

### 9.1 单测

| 用例 | 预期 |
|------|------|
| 两态分解自判 | 结构清晰→full；复杂任务→hierarchical；高不确定→不进 harness（ReAct） |
| 阶段细拆 | 到达阶段 2 仅拆当前阶段 task 列表，不臆测未到阶段 |
| Worker handoff 双写 | L1 尾部 append + H1 更新 |
| Worker 上下文隔离 | forWorker 含 taskGoal+constraints+toolWhitelist（v3：**不注入 L2**）；内部 think/tool 不回流 |
| H1 两级折叠（v3） | 注入块近 `near-keep-rounds` 轮原文，超阈值最老轮次 LLM 折叠为摘要 |
| Planner L1 组装一致性（v3） | Planner 复用 `ContextAssembler.assemble`（chat 含 L3），与普通 ReAct MAIN 差异仅 H1 注入块 + worker handoff |
| 触发式重规划边界 | 3 类显式触发（S6）；已完成 task 幂等跳过；max-phase-replans=3 收敛 |
| 崩溃恢复 | Redis load → IN_PROGRESS→FAIL→replan；task 状态一致 |
| 自判决策 | Planner selfAssess 0~1 分 → 续跑 / replan / 综合回答 |

### 9.2 Live

| # | 场景 | scene | 预期 |
|---|------|:---:|------|
| P1 | 分析 Q2 销售下降 + 改进方案 + 预算 | chat | Planner→Worker→自判→综合回答；步骤时间线卡片 |
| P2 | 修复 SQL 注入风险 + 单测 | task | Planner→Worker(内部 spawn)→自判→综合 |
| P3 | 静态 Workflow 回归 | / | `#knowledge-qa` DAG 展示正常（D3 保留） |
| P4 | 简单问答回归 | / | 走 ReactExecutor |
| P5 | 崩溃恢复 | chat | Kill orchestrator → 重启 → 恢复 Notebook → 继续 |
| P6 | 长任务上下文压缩 | chat | 6 轮后 H1 截断摘要生效（AgentScope Compaction + L1Compressor） |
| P7 | HIERARCHICAL 分层增量规划 | task | 首轮仅阶段骨架，调研完成后才细拆文件级任务 |

---

## 10. 风险与对策

| 风险 | 对策 |
|------|------|
| 删除旧 plan-workflow 影响静态 Workflow | D3 明确 DAG 画布保留服务静态 Workflow；`WorkflowExecutor` 独立于 `PlanWorkflowExecutor` |
| harness 灰度期无路由入口 | `agent.execution.harness.enabled` 开关 + 直接 API 接入，不依赖 L3 路由即可验证 |
| `AgentRole.WORKER` 破坏现有角色逻辑 | 新增枚举值不改现有 MAIN/SUB/PLANNER 行为；`resolveBridgeId` 加 WORKER 分支 |
| `AssembledContext.forWorker()` 上下文不足 | 稳定前缀（taskGoal + 共享快照 + handoff）+ toolWhitelist + query |
| 终态/审计分叉 | D8：复用 `ExecutionPlanStatus` + `PlanExecutionAuditService` |
| 前端两套时间线并存 | harness 卡片走 OperationStack（已有）；静态 Workflow 走 PlanWorkflowPanel（已有）；无共用组件改动 |

---

## 11. 关联文档

| 文档 | 关系 |
|------|------|
| [planner-harness-loop-design v8](./2026-07-31-planner-harness-loop-design.md) | **前置详设**：PlanNotebook / 触发式重规划细节（三态/双写/Checkpoint/Evaluator 等章节已被本 spec §0.1 简化决议 S1-S7 覆盖，v8 文档头部含 v9 修订注记） |
| [unified-routing-design v3](./2026-07-29-unified-routing-design.md) | planMode + scene + ResourceDispatcher |
| [expert-consultation-design](./archive/2026-07-07-expert-consultation-design.md) | peer-collab（已退役），spawn 中心化替代 |
| [plan-user-approval-design](./archive/2026-06-27-plan-user-approval-design.md) | **被 D5 废弃** |
