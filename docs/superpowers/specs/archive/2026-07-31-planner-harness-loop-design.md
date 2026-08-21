# Planner-Worker 架构 — Chat/Task 双模规划执行循环

> **⚠️ 已归档废案（2026-08-10）**：4.14 **唯一 SSOT** = [planner-executor-rebuild](../2026-08-05-planner-executor-rebuild-design.md)（S1–S7 / S5 v4 单一循环 / §5.0 PlanNotebook / §5.4 重规划）。本文保留仅供溯源；**勿再改、勿再按本文实施**（三态分解、Evaluator、MySQL 双写、H1 压缩点基建、`plan-phase` 等均已作废）。
>
> **原状态**：📋 设计评审中（已被上列 rebuild 取代）
> **日期**：2026-07-31
> **编号**：阶段四 P1 增量（Planner-Worker 架构：Chat 3-agent 分工 · Task Cursor 模式）· **归档**
> **v8（2026-08-02）**：三态分解（FULL/HIERARCHICAL/INCREMENTAL）——采纳「分层增量规划 + 触发式重规划」：阶段粗规划进 G 域稳定层（强模型 1 次/任务），到达阶段再细拆 task DAG（轻模型 `plan-phase`，N 次/任务）；重规划 5 类触发 + 4 条边界规则（局部修正/保留成果/上下文隔离/收敛控制）；H1 拆成「阶段骨架 Tier 1 + 阶段细节 Tier 2」。
> **v9（2026-08-05 · 简化决议覆盖，见 [2026-08-05-planner-executor-rebuild-design.md §0.1](../2026-08-05-planner-executor-rebuild-design.md#01-简化决议v2--2026-08-05)**：
>
> | v8 章节 | 简化决议 | 说明 |
> |---------|---------|------|
> | §0 术语「Evaluator / Chat 3-agent」 | **S1：砍独立 Evaluator** | Chat/Task 统一 Planner 自判，无 Maker-Checker |
> | §0.2、§4.1（三态分解） | **S5 v4：取消分解模式枚举** | 无 full/hierarchical/incremental；单一 Plan→Execute→Assess 循环；信息不足先调研再重规划；细则在 Worker（见 [rebuild §0.1 S5 / §3.3](../2026-08-05-planner-executor-rebuild-design.md)） |
> | §2.3.4 / §2.4（Tier 0/1/2 分层 + 压缩点） | **S3：去形式化分层与压缩点基建（仅 H1）** | run 内压缩用 AgentScope `CompactionMiddleware`；跨轮 L1 压缩用既有 `L1Compressor` + 压缩点模式（[五层 spec §5.5](../2026-07-31-unified-context-compression-design.md)）保持不动；H1 仅注入块 + rounds 超阈值截断摘要 |
> | §2.5.1 / §8.1（PlanSharedMemoryStore P2） | **S4：砍 P2 共享内存** | WorkerContextFactory 从 H1 rounds 按 taskId/dependsOn 读已完成 handoff |
> | §5.1（Redis+MySQL 双写 + C1-C4 checkpoint） | **S2：Redis 单写** | 删 MysqlWriter/Entity/Repository/DDL/version 重放；每轮结束 save 一次 |
> | §5.3.2/§5.3.3（RecoveryService / Orphan / 幂等重放） | **S2：恢复简化** | 恢复 = Redis load + IN_PROGRESS→FAIL→replan；复用 AgentScope StateStore 续跑 |
> | §4.2（PlanValidator） | **S7：harness 不复用 PlanValidator** | harness 用轻量结构校验；PlanValidator 留给静态 Workflow |
> | §5.2.2（5 类触发重规划） | **S6：收敛为 3 类显式 + 熔断** | 删信息缺口/资源溢出类与 plan-similarity 去重 |
>
> **v9 生效范围**：本文档 §0~§17 的细节描述与上表冲突处以**简化决议为准**；未列章节保持 v8 语义。
>
> **v10（2026-08-07 · 上下文契约定稿，对齐 [rebuild §3.1.1](../2026-08-05-planner-executor-rebuild-design.md)）**：① `forWorker()` **不注入 L2 用户画像**（§2.4/§4.3 已改，只含任务契约 + 定向上游 + P0/W0 只读子集）；② H1 注入块内部两级（§2.3.4 v11 注记：当前计划摘要 + 近 `near-keep-rounds` 轮原文，超阈值折叠为摘要，**无 `last_folded_round` 压缩点**）；③ Planner 的 L1 组装与普通 ReAct MAIN 完全一致（chat 含 L3 召回）。
> **v11（2026-08-10 · 对齐 rebuild S5 v4）**：正文 §0.2 / §4.1 / 术语表中 full|hierarchical|incremental、`completeness`、阶段细拆协议、`planner.phase` **一律作废**；以 rebuild §3.2/§3.3 单一循环为准。未改章节仅作历史详设参考。
> **前置**：
>   - [统一资源路由 v3](../2026-07-29-unified-routing-design.md) — `RoutingResult.planMode` + `RoutingResult.scene`（用户选择） + `ResourceDispatcher` 分发
>   - [ReAct 目标对齐与失败预算 4.7.7](../2026-07-27-react-goal-alignment-design.md) — `GoalAlignmentMiddleware` + `FailureBudgetMiddleware` + `AgentRunState`
>   - [ReAct 目标对齐 4.7.7](../2026-07-27-react-goal-alignment-design.md) · [archive/4.7.8](./2026-07-28-harness-loop-enhancement-design.md)（已归档，run 内见五层 §4.5）
>   - [Plan-Workflow 重试降级](../../../routing/plan-workflow-retry-degradation.md) — `PlanValidator` / `NodeRetryExecutor` / Plan 终态
>   - [多 Agent 统一设计](../2026-07-29-multi-agent-unified-design.md) — spawn_subagent 中心化编排 + `AgentRunRequest`
> **关联**：[ReAct TaskBoard 4.7.5](./2026-06-24-react-taskboard-design.md) · [ReAct Spawn Subagent 4.7.6](./2026-07-18-react-spawn-subagent-design.md) · [Cursor Agent Swarm](https://cursor.com/blog/agent-swarm-model-economics) · [Cursor Scaling Agents](https://cursor.com/blog/scaling-agents)
> **一句话**：用户选择 `scene`（chat/task）贯穿全链路。L3 路由输出 `planMode=harness` → PlannerHarnessExecutor。Planner 是 ReAct 主 Agent（全量上下文 L1+L2+H1），Worker 是其**工具调用**（`forWorker()` 丰富上下文），Worker 内部可 spawn 真正隔离的**子 Agent**（`forSubAgent=empty()`）。**v9 S1**：Chat/Task 统一 Planner 自判（无独立 Evaluator）。**v11 / rebuild S5 v4**：**无**分解模式枚举，单一边规划边执行循环。

---

## 0. 术语约定

| 术语 | 含义 |
|------|------|
| Planner-Worker 架构 | 双模规划执行循环：Plan → Execute → Evaluate → Loop。Planner 只规划不执行，Worker 只执行不规划 |
| Chat 模式（scene=chat） | ~~3-agent 分工：Planner + Evaluator~~ **v9 S1：砍独立 Evaluator**——Planner（规划+决策+综合）→ Worker（工具调用，`forWorker()` 丰富上下文）→ Planner 自判。适用场景：知识分析、方案制定等语义性任务 |
| Task 模式（scene=task） | Cursor 对齐：Planner（规划+决策+综合）→ Workers（工具调用，`forWorker()` 丰富上下文，可并行）→ handoff → Planner 直接决策。无独立 Evaluator。适用场景：编码、文件产出等可验证任务 |
| 规划轮次（Round） | Loop 的一次完整迭代 |
| PlanNotebook (H1) | 跨轮共享工作记忆：原始目标 + task 分解 + 每轮规划摘要 + Worker handoff 摘要 + 目标完成度（Chat 由 Evaluator 打分，Task 由 Planner 自判） |
| 全量分解（taskDecomposition=full） | Planner 首轮输出完整 Task Tree（completeness=closed）→ 逐个 task 工具调用 Worker → 回传评估 → 全部完成 → Planner 综合回答。**v9 S5：FULL 并入 HIERARCHICAL 执行路径**（首轮阶段骨架细度=任务粒度） |
| 阶段增量分解（taskDecomposition=hierarchical） | Planner 首轮仅输出阶段骨架（completeness=phase-closed，3~5 阶段 + 依赖 + 全局约束，只需原始需求）→ 阶段骨架写 G 域稳定层 → 到达当前阶段再基于前序真实产出细拆 task 列表（§4.1.1），阶段结束即归档 |
| ~~渐进式分解（taskDecomposition=incremental）~~ | **v9 S5：删除**——open 场景走既有 ReAct（planMode=none），不进 harness |
| Worker（Planner 的工具调用） | Planner 的**一次工具调用**（类似 `search_web` / `sandbox_exec`），拥有丰富上下文（`forWorker()`：明确目标 + 上游 Worker 关键结果 + 任务约束 + 工具白名单）。Worker 内部是 ReAct Agent，自主 think/tool 循环 |
| 子 Agent（Worker 内部 spawn） | Worker 内部通过 `spawn_subagent` 启动的**真正隔离子 Agent**（`forSubAgent()=empty()`），仅接收 spawn prompt（任务描述 + 输入）→ 输出结论，无上下文记忆 |
| Executor（调度层） | `PlannerHarnessExecutor` = 任务分发、依赖校验（`dependsOn`）、状态流转、重试降级、并行管控的**调度组件，不做推理决策**。是 §2.5 上下文隔离模型的**唯一出入口**：G 域读写、P1/P2 读写（§2.5.1）、S 域隔离、底层能力统一鉴权配额 |
| scene | 来自**用户选择**的会话场景（`chat` / `task`），前端传入 → `RoutingContext.scene` → L3 以 scene 调整 `planMode` 判定规则 + Planner 按 scene 区分 Chat/Task 模式 + Worker 按 scene 选择沙箱/工具策略 |

**三层 Agent 角色**：

| 层级 | 角色 | 上下文 | 能力 |
|---|---|---|---|
| L0 主 Agent | Planner = ReAct 主 Agent | **全量**：L1 对话历史 + L2 用户画像 + H1 PlanNotebook + Worker handoff，按 Tier 0/1/2 分层（§2.4） | 规划、调度 Worker、评估决策、综合回答 |
| L1 Worker | Planner 的工具调用 | **`forWorker()` 丰富上下文**：稳定前缀（Tier 0 + 任务目标 + 共享快照）+ toolWhitelist + query；upstream 结果经 `plan_shared_memory` 按需读取 | ReAct 自主循环、内部 spawn 子 Agent |
| L2 子 Agent | Worker 内部 spawn | **`forSubAgent()=empty()`**：仅 spawn prompt（任务描述 + 输入）→ 输出 | 单次执行，上下文隔离 |

### 0.1 架构层次：三层嵌套

```
同一会话、同一用户目标
┌──────────────────────────────────────────────────────────────────────────┐
│                         Planner (ReAct 主 Agent)                          │
│                         全量上下文：L1 + L2 + H1                            │
│                                                                          │
│  think(规划) → worker-1(工具调用) → observe(handoff)                       │
│                      │                                                   │
│                      ▼                                                   │
│           ┌─────────────────────────┐                                   │
│           │  Worker-1               │  ← forWorker() 丰富上下文             │
│           │  (ReAct Agent,          │     taskGoal + upstreamResults     │
│           │   Planner 的工具调用)     │     + constraints + toolWhitelist  │
│           │                         │                                   │
│           │  think → tool → observe  │                                   │
│           │            │                                                │
│           │            ├── spawn_subagent ──→ 子 Agent (forSubAgent=empty) │
│           │            │                      仅 prompt → 隔离输出         │
│           │            │                                                │
│           │            └── spawn_subagent ──→ 子 Agent (forSubAgent=empty) │
│           │                         │                                   │
│           │  ── handoff ────────────┘                                   │
│           └─────────────────────────┘                                   │
│                      │                                                   │
│  think(评估) ← observe(handoff)  ←── handoff 进入 Planner L1，视同工具结果  │
│                      │                                                   │
│        v9 S1：无独立 Evaluator——统一 Planner 自判（selfAssess）            │
│                      │                                                   │
│  done? YES → 综合回答  /  NO → replan → worker-2(工具调用) → ...           │
└──────────────────────────────────────────────────────────────────────────┘

Planner = 阶段角色 LLM 调用，不是子 Agent
Worker = Planner 的工具调用（forWorker()），视同 ReAct 的 tool 调用
子 Agent = Worker 内部 spawn（forSubAgent=empty()），真正上下文隔离
```

### 0.2 Full vs Hierarchical：决策机制（v9 S5：三态→两态）

不需要单独的意图识别步骤。Planner 首轮调用的自然产物决定分解模式——**规划粒度与信息完备度匹配**：信息越少规划越粗，信息越足规划越细（对齐 Claude Code Dynamic Workflows 的「分层增量规划」实践）：

```
Planner 被调用（首次，PlanNotebook 为空）
        │
        ▼
   Planner 尝试输出阶段级 Task Tree
        │
        ├──────────────────┬──────────────┐
        ▼                  ▼              ▼（open：不进 harness）
   完整 Task Tree     阶段完整、任务开放    仅第一步
   (closed)           (phase-closed)      (open)
        │                  │              │
        ▼                  ▼              ▼
   FULL（首轮骨架细度     HIERARCHICAL   → 既有 ReAct
     = 任务粒度）          （阶段粗规划       （planMode=none，
        │                    → 到达阶段        含 spawn/taskboard/
        │                    再细拆 task）     沙箱，S5 覆盖）
        │                  │
        └──── 两者执行机制相同：task 队列 + worker 调用 ────┘
```

| 模式 | 首轮输出 | 触发条件 | 说明 |
|------|----------|----------|------|
| **FULL** | 阶段骨架细度 = 任务粒度（`completeness=closed`） | 简单/已知任务，信息完备 | 执行路径与 HIERARCHICAL 相同（v9 S5 并入） |
| **HIERARCHICAL** | 阶段粗规划（`completeness=phase-closed`）：3~5 个阶段 + 依赖 + 全局约束 | **复杂任务默认**（绝大多数任务信息不完备，无法一次全拆但可定阶段） | 阶段骨架只依赖原始需求（成功率近 100%）→ 到达当前阶段时再基于前序真实产出细拆 task 列表（§4.1.1） |
| ~~INCREMENTAL~~ | ~~仅第一步（`completeness=open`）~~ | **v9 S5 删除**：完全开放/探索性任务（未知根因排查、陌生领域调研）走既有 ReAct | ReAct 已含 spawn/taskboard/沙箱，能力面覆盖；不进 harness |

**模型成本分层（对齐 [phase5 §5.3](../phase5-operation-openness-design.md)）**：HIERARCHICAL 下全局粗规划 `call_scene=plan`（强模型，1 次/任务，保质量）；阶段细规划 `call_scene=plan-phase`（轻量模型，N 次/任务，控成本）。同一 `HarnessPlanner` 组件，不新增角色。

---

## 1. 背景与问题

### 1.1 现状

| 路径 | 复杂度 | 规划模式 | 局限 |
|------|:-----:|---------|------|
| ReAct | 低~中 | LLM 自主 think→行动 | 长链路漂移、假完成、无全局视图 |
| 静态 Workflow | 中 | 预定义 DAG | 不可自适应 |
| Plan-Workflow | 中~高 | 一次性全量 DAG | 必须首轮预见全部步骤 |

核心缺口：**Planner-Worker（本 spec）填补"Planner 边执行边规划"的空档**——HIERARCHICAL 分层增量规划（首轮阶段粗规划 + 到达阶段再细拆，§0.2）+ INCREMENTAL 步进式兜底。

### 1.2 目标场景

| 场景 | 模式 |
|------|:---:|
| 「分析 Q2 销售下降原因，制定改进方案，评估预算影响」 | Chat |
| 「找出所有 SQL 注入风险，修复并写单测」 | Task |
| 「竞品功能对比，输出差异报告和改进建议」 | Chat |
| 「审查 50 页合同，标注风险条款，给出修改建议」 | Chat |

### 1.3 设计目标

1. Planner 不自己执行——只规划、决策、综合回答。Worker 不规划——只执行受控的单一 task
2. Planner = ReAct 主 Agent，拥有**全量上下文**（L1 对话历史 + L2 用户画像 + H1 PlanNotebook），按 Tier 0/1/2 分层（§2.4），记忆模型与普通 ReAct 主 Agent 一致
3. Worker = Planner 的**工具调用**（`forWorker()` 丰富上下文），不是隔离子 Agent。Worker 内部可 spawn 真正隔离的**子 Agent**（`forSubAgent=empty()`）
4. Chat 模式：3-agent 分工（Planner + Worker + Evaluator），Evaluator 独立评估以防确认偏误
5. Task 模式：对齐 Cursor 架构（Planner + Workers），Worker handoff 自包含评估信息，Planner 自判
6. 双模共享 Planner-Worker 骨架——差异仅为 Evaluator 存否
7. Planner 首调用自行判断 Full vs Incremental，路由层不感知
8. PlanNotebook (H1) 作为跨轮共享工作记忆，位于 Planner **Tier 2 尾部**（§2.4），压缩点窗口见 §2.3.4
9. Worker handoff **双写**：追加 Planner L1 尾部（视同 `tool_result`）+ 写入 H1（§2.3.3/§4.3），参与 L1 压缩点前进，**不重排 Near/Mid**
10. Synthesizer 合并到 Planner——Planner 已有 L1+H1，完全有能力综合回答

---

## 2. 架构与数据流

### 2.1 整体流程

```
用户输入 + RoutingResult(planMode=harness, scene=chat|task)
        │
        ▼
  ┌──────────────────────────────────────────────────────────────────────────┐
  │                     Planner (ReAct 主 Agent，全量上下文)                    │
  │                                                                          │
  │  上下文 = L1(对话历史) + L2(用户画像) + H1(PlanNotebook)                    │
  │                                                                          │
  │  ┌───────────────────────────────────────────────────┐                  │
  │  │  PlanNotebook (H1, 跨轮共享工作记忆)               │                  │
  │  │  · originalGoal / taskDecomposition / scene        │                  │
  │  │  · taskQueue[] / rounds[]                          │                  │
  │  │  · goalCompletion (Chat: Evaluator, Task: 自判)    │                  │
  │  └───────────────────────────────────────────────────┘                  │
  │                                                                          │
  │  ┌───────────────────────────────────────────────────┐                  │
  │  │  S1: Plan（Planner 自行判断 Full / Incremental）    │                  │
  │  │  Planner 输入：L1(对话) + L2(画像) + H1(Notebook)   │                  │
  │  └───────────┬───────────────────────────────────────┘                  │
  │              │                                                           │
  │     ┌────────┴────────┐                                                │
  │     ▼                 ▼                                                │
  │  FULL               INCREMENTAL                                        │
  │  (完整 Task Tree)    (仅第一步)                                          │
  │     │                 │                                                │
  │     ▼                 ▼                                                │
  │  ┌──────────────────────────────────────────────────┐                  │
  │  │  S2+S3: Validate + Execute（工具调用 Worker）      │                  │
  │  │  · Full: taskQueue 逐个消费 / 可并行               │                  │
  │  │  · Incremental: 执行本轮节点 / 可并行               │                  │
  │  │  · Worker = Planner 的工具调用（forWorker 丰富上下文） │               │
  │  │  · Worker 内部 ReAct → 可 spawn 子Agent(empty)      │               │
  │  │  · handoff 回 Planner L1，视同 tool_result          │               │
  │  └──────────────────────┬───────────────────────────┘                  │
  │                         │                                               │
  │              ┌──────────┴──────────┐                                   │
  │              ▼                     ▼                                   │
  │     ┌──────────────┐     ┌──────────────────┐                          │
  │     │  Chat 模式    │     │   Task 模式        │                          │
  │     │  (scene=chat) │     │   (scene=task)    │                          │
  │     │              │     │                   │                          │
  │     │  S4:         │     │  Planner 直接      │                          │
  │     │  Evaluator   │     │  收 handoff        │                          │
  │     │  独立打分     │     │  自判完成度         │                          │
  │     │  0.0-1.0     │     │                   │                          │
  │     └──────┬───────┘     └────────┬──────────┘                          │
  │            │                      │                                      │
  │            └──────────┬───────────┘                                      │
  │                       ▼                                                  │
  │              ┌─────────────────┐                                        │
  │              │  Planner 决策:   │                                        │
  │              │  · done?        │                                        │
  │              │    YES → 综合回答                                         │
  │              │    NO  → replan next round                               │
  │              └─────────────────┘                                        │
  └──────────────────────────────────────────────────────────────────────────┘
```

**Chat vs Task 模式对比**：

| | Chat 模式 (scene=chat) | Task 模式 (scene=task) |
|---|---|---|
| Evaluator | ✅ 独立 LLM 评估（Maker-Checker） | ❌ Planner 自判（handoff 含评估信息） |
| Worker 并行 | ✅ | ✅（独立 repo 副本） |
| Timeline | 流式展示 | 流式展示（无区别） |
| Planner 职责 | 规划 + 决策 + 综合回答 + 评估接收 | 规划 + 决策 + 综合回答 |
| Planner 记忆 | L1 + L2 + H1（同普通 ReAct MAIN） | L1 + L2 + H1（同普通 ReAct MAIN） |
| Worker 上下文 | `forWorker()` 丰富上下文 | `forWorker()` 丰富上下文 |
| Worker 内部子 Agent | `forSubAgent=empty()` 隔离 | `forSubAgent=empty()` 隔离 |
| 适用场景 | 语义性任务（分析、方案制定） | 可验证任务（编码、文件产出） |
| 决策依据 | Evaluator 打分 + H1 | Worker handoff + H1 |

**结论**：`Chat = Task + Evaluator`。两个模式共享完全相同的 Planner（全量上下文）、Worker（工具调用）、PlanNotebook 骨架。`scene` 来自用户选择（前端传入），贯穿 `RoutingContext` → `RoutingResult` → Planner 全链路。

---

### 2.2 数据模型

#### PlanNotebook

```java
package com.sunshine.orchestrator.plan.harness;

import com.sunshine.orchestrator.plan.PlanJson;
import java.time.Instant;
import java.util.*;

/**
 * Planner-Worker Loop 跨轮共享工作记忆 (H1)。
 * 叠加在 Planner 全量上下文（L1+L2+H1）之上，Chat 和 Task 模式共用。
 */
public class PlanNotebook {
    /** 原始用户目标 */
    private final String originalGoal;
    /** 用户原始 query */
    private final String userQuery;
    /** 执行场景：chat | task（来自用户选择）*/
    private String scene;
    /** 分解模式：full | hierarchical | incremental（§0.2 三态） */
    private String taskDecomposition;
    /** HIERARCHICAL：阶段骨架（3~5 个阶段 + 依赖 + 全局约束），G 域稳定层（§4.1.1） */
    private List<Phase> phases;
    /** HIERARCHICAL：当前阶段索引，阶段完成即前移 */
    private int currentPhaseIndex = 0;
    /** Full 模式的任务队列 */
    private final Deque<TaskItem> taskQueue = new ArrayDeque<>();
    /** 所有已完成轮次 */
    private final List<RoundRecord> rounds = new ArrayList<>();
    /** 当前目标完成度（Chat: Evaluator 打分, Task: Planner 自判） */
    private double goalCompletion = 0.0;
    /** 下一轮建议方向 */
    private String nextDirection;
    /** 创建时间 */
    private final Instant createdAt = Instant.now();

    // ---- 边界控制 ----
    private int maxRounds = 5;
    private int maxTotalTasks = 10;
    private int maxTotalNodes = 20;
    private int currentRound = 0;
    private int totalTasksCompleted = 0;
    private int staleRounds = 0;

    // ---- 便捷方法 ----
    public boolean isChat() { return "chat".equals(scene); }
    public boolean isTask() { return "task".equals(scene); }
    public boolean isFull() { return "full".equals(taskDecomposition); }
    public boolean isHierarchical() { return "hierarchical".equals(taskDecomposition); }
    public boolean isIncremental() { return "incremental".equals(taskDecomposition); }
    public Optional<TaskItem> nextTask() { return Optional.ofNullable(taskQueue.pollFirst()); }
    public boolean hasMoreTasks() { return !taskQueue.isEmpty(); }
    public void requeueTask(TaskItem task) { taskQueue.addFirst(task); }
    /** HIERARCHICAL：当前阶段；phase-closed 时首轮只有骨架，taskQueue 由阶段细拆填充 */
    public Optional<Phase> currentPhase() {
        return (phases == null || currentPhaseIndex >= phases.size())
                ? Optional.empty()
                : Optional.of(phases.get(currentPhaseIndex));
    }
    public void advancePhase() { currentPhaseIndex++; }

    public boolean shouldStop() {
        return currentRound >= maxRounds
                || staleRounds >= 2
                || (isFull() && totalTasksCompleted >= maxTotalTasks);
    }

    public String renderForPlanner() {
        StringBuilder sb = new StringBuilder();
        sb.append("## 原始任务\n").append(originalGoal).append("\n\n");
        sb.append("## 执行场景\n").append(isChat() ? "Chat (3-agent)" : "Task (Cursor)").append("\n\n");
        if (isHierarchical() && phases != null) {
            sb.append("## 阶段骨架\n");
            for (int i = 0; i < phases.size(); i++) {
                Phase p = phases.get(i);
                sb.append(i == currentPhaseIndex ? "▶ " : "  ")
                  .append(i + 1).append(". ").append(p.name())
                  .append(" [").append(p.status()).append("]")
                  .append(p.dependsOn().isEmpty() ? "" : " 依赖: " + p.dependsOn())
                  .append("\n");
            }
            sb.append("\n");
        }
        sb.append("## 已执行步骤\n");
        for (RoundRecord r : rounds) {
            sb.append("### 第 ").append(r.roundIndex()).append(" 轮\n");
            if (r.task() != null) sb.append("**Task**：").append(r.task().label()).append("\n");
            for (NodeResult nr : r.nodeResults()) {
                sb.append("- [").append(nr.status()).append("] ").append(nr.summary()).append("\n");
            }
            sb.append("**完成度**：").append(r.roundGoalCompletion()).append("\n");
        }
        sb.append("目标完成度：").append(goalCompletion).append("\n");
        if (nextDirection != null) sb.append("下一步方向：").append(nextDirection).append("\n");
        return sb.toString();
    }

    // ---- 内部类型 ----
    public record RoundRecord(int roundIndex, TaskItem task, PlanJson plan,
            List<NodeResult> nodeResults, double roundGoalCompletion, String evaluatorReason) {}
    public record TaskItem(String taskId, String label, PlanJson plan, String status) {}
    public record NodeResult(String nodeId, String nodeType, String status, String summary, String detail) {}
    /** HIERARCHICAL：阶段骨架节点（G 域稳定层）；status: pending|in-progress|done|archived */
    public record Phase(String phaseId, String name, String goal, List<String> dependsOn,
            String status, int replanCount) {}
}
```

#### RoutingResult 扩展（v3 路由设计追加）

```java
// RoutingResult 新增 planMode 字段（L3 输出）：
String planMode;   // "none" | "harness"
// none:    ReactExecutor（通用 ReAct）
// harness: PlannerHarnessExecutor（Planner-Worker Loop）

// RoutingResult.scene 来自用户选择（前端传入），不在 RoutingResult 中新增：
// scene = "chat" → Planner-Worker Chat 模式（含 Evaluator，Maker-Checker）
// scene = "task" → Planner-Worker Task 模式（Planner 自判，Cursor 对齐）

// scene 同时决定：
//   1. Planner-Worker 模式（Chat vs Task，即 Evaluator 有无）
//   2. L3 分类器的 planMode 判定规则（chat→语义复杂度，task→工程复杂度）
//   3. Worker 的沙箱/工具策略

// ResourceDispatcher 新增分支：
// planMode=none  → ReactExecutor
// planMode=harness → PlannerHarnessExecutor（Chat/Task 由 RoutingResult.scene 区分）

// 注意：taskDecomposition（full vs hierarchical vs incremental）不在 RoutingResult 中。
// 它是 Planner 首轮调用的输出（PlanJson.completeness 三态），路由层不感知。
// PlanJson 扩展：completeness 增加 "phase-closed"，新增 phases[]（阶段骨架，§2.2）。
```

---

### 2.3 Planner 记忆模型：全量上下文 + H1 叠加

**核心原则**：Planner 是 ReAct 主 Agent，记忆模型与普通 ReAct MAIN **完全一致**，在此基础上叠加 H1 PlanNotebook。

#### 2.3.1 上下文构成

```
Planner 全量上下文 = L1(对话历史) + L2(用户画像) + H1(PlanNotebook)

L1: 多轮对话 + Planner think/tool/observe + Worker handoff (视同 tool_result)
L2: 用户偏好、角色、组织信息（同普通 ReAct MAIN）
H1: renderForPlanner() — 原始目标 + 已完成轮次摘要 + 当前进度
```

#### 2.3.2 压缩对比：普通 ReAct MAIN vs Planner-Worker

| | 普通 ReAct MAIN | Planner-Worker Planner |
|---|---|---|
| L1 内容 | user→think→tool→observe→...→answer | user→think→worker_invoke→handoff_observe→think→worker_invoke→handoff_observe→...→answer |
| tool 等价物 | `search_web("xxx")` → `observe(result)` | `worker("任务X", ctx)` → `observe(handoff)` |
| L1 压缩 | tool result 参与 L1→L2 压缩 | Worker handoff 参与 L1→L2 压缩（同 tool result） |
| L2 压缩 | 跨轮结构化摘要 | 同（用户画像部分不变） |
| H1 压缩 | 无 | Near/Mid/Far 窗口：Mid 结构摘要（仅 summary），Far LLM 合并 |
| Worker 内部 | 无 | Worker 自己的 L1 scratchpad，**不进入 Planner 上下文** |

> **v6 注记（prefix 稳定性）**：Worker handoff 在 Planner L1 中**天然处于 tail 位置**（`assistant(tool_call) → observe(handoff)` 始终 append 在消息末尾，C2 友好）。**禁止**在 L1 压缩时把旧 handoff 挪入中段或重排——L1 压缩点前进（对齐 [五层 spec §5.5](../2026-07-31-unified-context-compression-design.md)）应把旧 handoff 折叠进 Far 摘要，而非重排 Near/Mid。

#### 2.3.3 Worker 记忆隔离

Worker 是 Planner 的工具调用，拥有自己的 ReAct 循环和 L1 scratchpad。但 Worker 的 think/tool 细节**不回流到 Planner 上下文**——只有 handoff 摘要进入 Planner L1：

```
Planner 看到的:   worker("收集竞品") → observe(handoff: "三家定价已获取，A:$99, B:$129, C:未公开")
Worker 内部的:    think→tool(search_web)→observe→think→tool(search_db)→observe→handoff
                  ↑ 这些留在 Worker 自己的 L1 scratchpad，Planner 看不见也不关心
```

**当 Planner L1 上下文爆满时**：Worker handoff 参与 L1 压缩管线（同普通 ReAct 的 tool_result），压缩点前进时最近的保留全文，远的在 L2 压缩时只保留 handoff 结论摘要。Worker 内部的 ReAct 细节则继续躺在 Worker 的 scratchpad 中。

> **v6 注记（handoff 双写）**：Worker handoff 除追加 Planner L1 尾部外，**同时写入 H1 PlanNotebook**（见 §2.4）：L1 尾部副本给当前 ReAct run 即时决策，H1 副本给后续轮次 / follow-up 共享。二者职责不同——L1 是「本轮执行轨迹」，H1 是「跨轮共享状态」，**不做单写合并**（单写 L1 则 follow-up 轮丢失执行细节，单写 H1 则当前 run 的 tool_result 反馈延迟一轮）。

#### 2.3.4 H1 PlanNotebook 压缩

H1 自身的压缩策略（与 `2026-07-31-unified-context-compression-design.md` 的五层管线互补）：

| 窗口 | 触发阈值 | 策略 |
|---|---|---|
| **Near**（最近 N 轮） | `rounds.size() ≤ 3` | `renderForPlanner()` 全文 |
| **Mid**（中间轮次） | `rounds.size() > 3` | 结构截断：每轮仅保留 `task.label + summary + goalCompletion`，不保留 NodeResult detail |
| **Far**（远期轮次） | `rounds.size() > 6` | LLM 合并：多轮合并为一段摘要 `"第 1-3 轮完成了竞品信息收集和分析，完成度 0.85"` |

H1 压缩与 Planner L1 压缩**独立触发**。L1 压缩看 token 预算，H1 压缩看 round 数量。两者无耦合。

> **v6 注记（压缩点模式）**：H1 的 Near/Mid/Far 为**固定轮数窗口**（3/6），每次 Mid 截断都会改变 H1 块字节 → 破坏 Planner Tier 2 prefix。对齐 [五层 spec §5.5](../2026-07-31-unified-context-compression-design.md) 压缩点模式：H1 引入**压缩点**（`last_folded_round`），Near = 压缩点之后的轮次原文，Mid/Far = 压缩点之前（结构截断 + LLM 合并），触发压缩时压缩点前移一次重建。由于 H1 位于 Planner 上下文 **Tier 2 尾部**（§2.4），其变化只 miss 尾部小块，不影响 Tier 0/1。

> **v8 注记（H1 拆分：骨架与细节分层）**：HIERARCHICAL 模式（§0.2/§4.1.1）下 H1 **拆成两块**——**阶段骨架**（3~5 阶段 + 依赖 + 全局约束）只在阶段切换时变 → **Tier 1 幂等 upsert**（真变才失效一次，字节稳定跨阶段可复用）；**当前阶段 task 细节 + handoff 摘要**每轮追加 → **Tier 2 尾部**（变化只 miss 尾部小块）。比「H1 整体沉 Tier 2 尾部」更优：阶段骨架稳定时上移 Tier 1，跨阶段前缀复用更好。FULL/INCREMENTAL 模式无阶段骨架，H1 仍整体在 Tier 2 尾部。
>
> **v11 注记（2026-08-07 · 本表被 [rebuild S3](../2026-08-05-planner-executor-rebuild-design.md) 作废）**：上表 Near/Mid/Far 窗口 + v6 `last_folded_round` 压缩点**均不落地**。H1 现行落地（rebuild §3.1.1 定稿）：注入块内部**两级**——阶段骨架 + 近 `near-keep-rounds`（默认 6）轮原文逐轮追加，超阈值最老轮次 LLM 折叠为摘要；无 `last_folded_round`、无独立 Near/Mid/Far 窗口，`near-keep-rounds` 与 L1 压缩窗口（chat 4+4 / task 2+2）无关。

#### 2.3.5 跨轮用户交互

当用户在 Planner-Worker 执行中发送 follow-up（如「等一下，我说的竞品不是 A 公司，是 B 公司」）：

```
Planner 新一轮输入:
  L1: follow-up 消息 + 上一轮 Planner 状态
  H1: 当前所有已完成轮次（可能部分 Worker 未完成）

Planner 决策:
  - 如果当前有运行中的 Worker → Planner 决定是否中断或等其完成
  - 如果目标变更 → 更新 H1.originalGoal，标记受影响 task 为 obsolete
  - 如果是增量指令 → 追加到 H1，视同新任务
```

#### 2.4 上下文分层分配（v6 优化 · 压缩点模式适配）

> 对齐 [unified-context-compression-design §5.5](../2026-07-31-unified-context-compression-design.md) 的 Tier 0/1/2 分层与压缩点模式。Planner 是唯一有跨轮 KV 缓存包袱的角色（多轮 ReAct run、每轮多次 LLM 调用），**分层只应用于 Planner 与 Worker 稳定前缀**。
>
> **双视角关系**：本节按**变化频率**分层（Tier 0/1/2，KV 缓存视角）；§2.5 按**共享范围**隔离（G 全局 / P 任务计划 / S 子任务）。两维正交——本节 Tier 2 尾部 = S 域入口，本节 Tier 0/1 = G 域主体，H1 = P1 载体（**注：P2 / 分层分解已由 rebuild S3/S4/S5 作废**）。

**Planner 上下文（主 Agent，按变化频率分层）：**

```
Tier 0 · 会话级绝对稳定（每轮 worker 调用字节不变，跨用户可复用）
  tools（租户固定：RAG + 沙箱 + spawn，确定性排序）+ system base
  + scene-overlay.{kind} + mode-overlay + hitl-overlay
  + 官方 skill 市场目录摘要（system 级，全局共有，见 §2.4.2）

Tier 1 · 会话/用户级低频（幂等 upsert，真变才失效一次）
  + personal-rules（soul）+ 个人 skills 配置（user_skill_binding）
  + L2 用户画像 + P0 项目规范（task 专属）+ Far/Mid 摘要

Tier 2 · 每轮动态段（tail append，只 miss 尾部小块）
  + Near 原文（压缩点之后逐轮增长）
  + H1 PlanNotebook（高频，下沉到尾部，压缩点模式见 §2.3.4）
  + 命中 skill overlay + 意图/模式注入（尾部 system）+ user query
```

**关键：Worker handoff 双写**（见 §2.3.3 / §4.3）——handoff 以 `observe` 追加在 L1 尾部（ReAct 天然 tail append），同时写入 H1 供后续轮次共享。**不注入 L1 中段、不重排 Near/Mid**。

**Worker 上下文（`forWorker()`，跨 worker 可继承稳定前缀）：**

```
稳定前缀（同一 plan run 内字节不变，跨 worker 复用）
  Tier 0（同 Planner，除 spawn 类主 Agent 专属工具）
  + 任务目标（Planner 下发）+ 共享区当前状态快照（plan_shared_memory，只读）
  + P0 项目规范 / W0 只读子集（task）

动态段（每个 worker 各不相同）
  + 本 worker 的 toolWhitelist（绑定 skill 工具集）+ query
```

> **v10 注记（2026-08-07 · 上下文契约定稿，对齐 [rebuild §3.1.1](../2026-08-05-planner-executor-rebuild-design.md)）**：`forWorker()` **不注入 L2 用户画像**——Worker 是任务执行器，只需任务契约（taskGoal/constraints/expectedOutput/successCriteria）；L2 对 Worker 是纯 Token 开销且可能污染任务定向（§2.5.3 规则 3 一致）。上游结果按 `dependsOn` 定向渲染**动态段**，禁止进稳定前缀（KV 红线，§2.5.3 规则 6）。

`upstreamResults` 不再逐 worker 全量注入（避免 vLLM 实证的 append 全失效 → 每个 worker 都 miss），改由 Worker 通过 `plan_shared_memory` 按需读取。Worker 子会话数不受 L1 压缩点影响（子会话各自独立）。

##### 2.4.1 H1 在 Tier 2 尾部的原因

H1 每轮 round 结束时**必然追加**（NodeResult + goal 更新），属**高频动态块**。若放入中段（原 §2.3.1 隐式位置），每次追加都会重排后序上下文 → 全量 miss。放 Tier 2 尾部后：追加仅影响尾端，且 H1 是 Planner 决策最关键信息，tail 位置同时获得最高 attention 权重（缓解「中间迷失」）。

##### 2.4.2 Skill / 插件市场映射（承接插件菜单需求）

| 来源 | 层级 | 作用对象 | 说明 |
|------|------|----------|------|
| 官方市场 **system skills**（全局共有） | Planner **Tier 0** | 目录摘要进前缀 | 所有人共享，字节稳定，不进 Near |
| 官方市场 **user skills**（个人选配） | Planner **Tier 1** | 个人配置块 | `user_skill_binding` 幂等 upsert |
| 用户**自定义 skills** | Planner **Tier 1** | 个人配置块 | 同上 |
| 命中 skill 正文（`systemOverlay`） | Planner **Tier 2** | 尾部 system | 随命中动态变化，放尾部 |
| 业务工具 / MCP | Worker 工具集 / spawn 子 Agent | 动态 | 子会话无前缀包袱，不占 Planner 预算 |
| 工具规模 > 阈值 | **Planner Tier 0 名列表 + Tier 2 schema** | 候选集 | 工具检索（[phase5 §5.5](../phase5-operation-openness-design.md)）由 **Planner 检索生成候选集 → 下发 toolWhitelist 给 Worker**；Worker 不二次检索 |

前端「插件」菜单（关联 [task-scene spec](../2026-08-01-task-scene-context-design.md) §7.4）的 system/user 级标识即对应 Tier 0 / Tier 1 划分：**system 级**=后台统一开启、全局共有（进 Tier 0 前缀）；**user 级**=个人自选或自建（进 Tier 1 幂等块）。Worker 的 `toolWhitelist` 是 Planner 动态下发的（`call_scene=worker` 走 5.3 快模型分层，见 [phase5 §5.3](../phase5-operation-openness-design.md)）。

#### 2.5 上下文隔离边界：三层模型（v7 细化）

> §2.4 解决「上下文**按变化频率**分层」→ KV 缓存（时间维度）；本节解决「上下文**按共享范围**隔离」→ 风险与 Token（空间维度）。**两维正交**：任何上下文块都可同时落在「某个 Tier」×「某个隔离域」。

调研对齐（Claude Code / Cursor / Codex / 工单自动化等 Planner-Executor 实现共识）：`全局共享（只读为主）→ 跨任务按需传递（DAG 定向）→ 单任务完全隔离（用完即毁）`。

##### 2.5.1 三层定义

| 层 | 归属 | 内容 | 读写权限 | 生命周期 |
|----|------|------|----------|----------|
| **G 全局会话上下文** | 编排层（PlannerHarnessExecutor + Planner） | 用户原始需求、最终业务目标、全局约束（权限/TTL/合规/超时）、Planner 输出计划 + 依赖 DAG、会话凭证、task 场景的 W0 工作区记忆 + P0 项目规范 | Planner / Executor / Evaluator 读写；**Worker 只读任务级子集（按需，非全量）** | 会话级 |
| **P 任务计划上下文** | Executor 调度器 | **P1 = H1 PlanNotebook**：原始目标 + task 分解 + 每轮规划摘要 + handoff 摘要 + 完成度（跨轮持久化，Redis + MySQL + Checkpoint）<br>**P2 = `plan_shared_memory`**：已完成子任务的**标准化产出** + 中间业务变量 + 进度快照（run 内临时，按 `dependsOn` 定向） | Executor 读写；Worker 只读分配给自己的前置结果（P2 经 S 动态段按需读取，**不进稳定前缀**） | P1 跨轮持久化；P2 run 结束即清 |
| **S 子任务执行上下文** | 单个 Worker 执行单元 | 当前子任务专属目标、执行指令、边界约束、ReAct 推理链、工具调用记录、临时变量、任务内重试记录；内部可再 spawn **S' 子 Agent**（`forSubAgent()=empty()`，最严格隔离） | 仅本 Worker（或本子 Agent）读写 | 任务结束即销毁 |

##### 2.5.2 映射到本 spec 已有概念

```
G 全局会话上下文 = Planner Tier 0/1 静态层（tools/base/overlay/P0/L2/Far/Mid）
                  + 会话 L1 中「用户 query + 综合回答」骨架
                  + task 场景的 W0 工作区记忆（跨会话共享项目索引/约束/事实）
P1 跨轮共享记忆  = H1 PlanNotebook（原始目标 + task 分解 + 轮次摘要 + handoff 摘要 + 完成度）
P2 run 内共享    = plan_shared_memory（按 dependsOn 定向传递的 upstreamResults / 中间变量）
                  + T0 任务进度摘要（task 场景，随压缩点降频）
S 子任务执行上下文 = forWorker() 动态段（taskGoal + toolWhitelist + query）
                  + Worker 内部 L1 scratchpad（think/tool/observe，不回流）
                  + S' 子 Agent（forSubAgent=empty()，仅 spawn prompt → 输出）
```

**关键对应**：Worker handoff **双写**（§2.3.3/§4.3）=「S → P/G」的唯一出口——S 域中间推理全丢弃，仅**标准化产出**（handoff 摘要）写入 P1（H1）与 Planner L1 尾部（G 域）；执行完成后 S 域即时销毁。隔离是**四层嵌套**：G（全量，仅 Planner/Executor/Evaluator）⊃ P（P1/P2 共享）⊃ S（单 Worker）⊃ S'（子 Agent，`empty()` 最严格）——S' 是项目里隔离最彻底的一层。

##### 2.5.3 隔离规则（本 spec 落地约束）

1. **非全量共享，按 DAG 定向传递**：`plan_shared_memory`（P2）仅把**前置依赖任务**的产出注入后续任务；无依赖的并行 Worker 之间**完全不可见**（对齐 §4.3 `dependsOn`）。
2. **只传最终结果，不传中间推理**：跨任务注入的是标准化产出（结果契约），ReAct 推理链/失败尝试**不跨任务透传**（S 域私有）。
3. **按需子集读取，唯一写入口**：Worker 对 G 域只读**任务级子集**（taskGoal + constraints + toolWhitelist + 共享快照），**不注入全量 G 域**（L2 画像/Far/Mid 对 Worker 是纯 Token 开销）；禁止写全局上下文，唯一写入口是 handoff 双写（经 Executor 统一管控）。
4. **失败闭环 + 审计分级**：Worker 异常/重试仅在 S 域内闭环（错误栈不扩散）；**S 域留执行态**（本任务重试用）、**P1 留结构化异常摘要**（审计）、**G 域干净**；重试/降级由 Executor（`taskRetryMax` + replan）统一处理。
5. **底层能力共享但统一鉴权**：模型层（llm-gateway）、工具层（tool-manager）、记忆层（Redis/MySQL）、缓存层全链路共享，但**所有访问经 Executor 统一鉴权与配额管控**——toolWhitelist（Planner 下发）、HITL、`call_scene` 模型分层（phase5 5.3）、Token 预算——不直接暴露给 S 域执行单元。
6. **KV 红线：定向传递只进 S 动态段，不进稳定前缀**：P2 按需读取结果必须渲染在 Worker **动态段（query 附近）**，**禁止**写入 Worker 稳定前缀——否则每个 Worker 前缀字节不同，跨 worker 前缀复用全失效（每个都 miss，vLLM 实证）。对齐 §2.4。

> **S 域不是无界**：Worker 内部 ReAct 循环同样有 Token 预算，需 compaction/eviction（对齐五层压缩点 / 4.7.8）——「完全隔离」≠「无界增长」，S 域内部裁剪不回流 P/G 域。

##### 2.5.4 分场景差异（task 编码 / chat 企业）

| 场景 | 共享侧重（G/P） | 隔离侧重（S） | 特殊机制 |
|------|----------------|---------------|----------|
| **task 编码** | 项目结构 / 代码索引 / 技术栈约束 / 编码规范 / 依赖版本（P0 项目规范 + W0 工作区记忆，均进 G 域） | 每文件/模块修改独立 S 域，A 文件的调试逻辑不透传 B 文件；并行改不同模块完全隔离，避免同文件覆盖冲突 | **写隔离由 `agent_workspace` checkout 承载**（用户显式选主分支/worktree，[task-workspace-codex](./2026-07-28-task-workspace-codex-design.md) §2.3）——**不引入应用层读写锁**（业界验证锁是错误抽象，冲突语义交给 Git + 用户）；临时代码不写全局，仅最终合入回传 |
| **chat 企业** | 业务目标 / 身份权限 / 合规规则 / 审计 traceId | 不同系统工具调用隔离（内部库查询结果不透传外部 API 工具）；高权限操作中间结果不向低权限任务透传 | 上下文流转全程留痕（审计）；敏感数据跨任务传递前自动脱敏（[desensitize 服务](../2026-07-27-observability-enhancement-design.md)） |

##### 2.5.5 与纯 ReAct 的核心区别

| 维度 | 纯 ReAct | Planner-Worker（本 spec） |
|------|----------|---------------------------|
| 上下文结构 | 单上下文全程累加，每轮携带全量历史 | 四层嵌套（G/P/S/S'）：子任务隔离 + 按需传递（§2.5.1） |
| Token 增长 | 任务越长膨胀越严重 | 控制在单任务维度（S 域用完即毁 + 内部 compaction） |
| 相互干扰 | 历史推理互相污染 | 逻辑污染架构性避免（S 域完全隔离） |
| 风险扩散 | 错误上下文扩散到后续 | 失败闭环在 S 域，Executor 统一重试降级 |
| 共享方式 | 全量历史对每次决策可见 | P2 按 DAG 定向注入动态段，稳定前缀跨 worker 复用（KV 红线） |

---

## 3. 复杂度识别：L3 路由

### 3.1 判定维度

L3 LLM 分类器输出 `planMode`。`scene` 来自用户选择（前端传入），作为 L3 输入参数参与判定规则：

| 维度 | planMode=none | planMode=harness |
|------|:---:|:---:|
| 认知步骤数 | ≤3 | ≥4 或不确定 |
| 依赖链 | 线性 | 多级依赖 |
| 验证需求 | 无 | 需要验证闭环 |
| 不确定性 | 路径明确 | 需探索后再定方向 |

**scene 影响 planMode 判定**：
- `scene=chat`：偏向语义复杂度（分析深度、需交叉验证→harness）
- `scene=task`：偏向工程复杂度（修改文件数、跨模块→harness）

**安全网**：没把握判定 harness → 走 none（静默 REAct），宁漏勿错。

### 3.2 L3 分类器输出

```json
{
  "type": "AGENT",
  "agentIds": ["analyst"],
  "planMode": "harness",
  "confidence": 0.85
}
```

**planMode 为空或 none 时**：走 ReactExecutor（通用 ReAct）。`scene` 决定加载的工具集（chat→知识分析工具 / task→编码工具）。

**注意**：
- `scene` 来自用户选择（前端 `ChatController` 请求体），L3 **不作为输出**
- L3 不判断 taskDecomposition（full vs hierarchical vs incremental）。Planner 首调用自行决定（completeness 三态）
- 去掉 `is_no_match`：agentIds/skillIds 为空 → `planMode=none` → 静默 REACT，无需额外标志位

### 3.3 指代消解策略

指代词（"那个"、"第一个"、"继续"）在 L0-L2 不可能命中任何资源。深层语义兜底（L3 模式 B）用**全量 L1 上下文 + 完整 Catalog**一次 LLM 调用完成指代消解和 planMode 判定。

**不做小模型补全**。理由：
1. 深层语义兜底已覆盖此场景（500-800ms），小模型补全后仍需走 L3 快速分类（250-600ms），节省有限
2. 小模型补全错误（"那个"指错目标）→ 后续链路全偏，风险大于收益
3. 指代词是低频场景——大部分用户输入有明确关键词，L0/L1 即命中
4. 若未来指代词比例上升，优化方向是增强 L2 embedding 索引质量而非加一层小模型

---

## 4. 各阶段详细设计

### 4.1 S1: Plan — 双模规划 + Full/Hierarchical/Incremental 三态自判

Planner 是 ReAct 主 Agent，拥有**全量上下文**（与普通 ReAct MAIN 一致）：

- **L1**：对话历史（用户多轮消息 + Planner 综合回答 + 之前 Worker 的 handoff 作为工具结果）
- **L2**：用户画像（偏好、角色、组织信息）
- **H1**：PlanNotebook（`renderForPlanner()`：原始目标 + 阶段骨架 + 已完成轮次摘要 + 进度）

```java
// PlannerHarnessLoop.start() —— 两态决策（§0.2；v9 S5 删 open 分支，open 场景走既有 ReAct）
PlanJson plan = harnessPlanner.planFirstRound(notebook, fullContext);
// fullContext = assembledContext(L1 + L2) + notebook.renderForPlanner()

switch (plan.completeness()) {
    case "closed" -> {                    // FULL：信息完备，阶段骨架细度=任务粒度
        notebook.setTaskDecomposition("full");
        plan.taskList().forEach(task -> notebook.getTaskQueue().add(task));
        executeTasks(notebook);
    }
    case "phase-closed" -> {              // HIERARCHICAL：阶段粗规划（默认复杂任务）
        notebook.setTaskDecomposition("hierarchical");
        notebook.setPhases(plan.phases());
        decomposeCurrentPhase(notebook);  // 到达阶段 1 即细拆（§4.1.1）
        executeTasks(notebook);
    }
}
```

**后续轮次 Planner 输入**（两态通用；v9 S3 去 Tier 形式化分层）：稳定前缀 + Near 原文 + H1 注入块 + 本轮 query。记忆与普通 ReAct MAIN 的跨轮压缩管线完全一致，采用既有**压缩点模式**（[五层 spec §5.5](../2026-07-31-unified-context-compression-design.md)）：当 L1 上下文爆满时，Worker handoff 视同 tool_result 参与压缩点前进（旧 handoff 折叠进 Far，**不重排 Near/Mid**）。H1 仅注入块，rounds 超阈值时简单截断为摘要（v9 S3，不建压缩点窗口）。

#### 4.1.1 HIERARCHICAL：阶段细拆（走到哪、规划到哪）

```
阶段骨架（首轮，G 域稳定层）
  [代码调研] → [方案设计] → [代码实现] → [回归验证]      ← 3~5 阶段 + 依赖 + 全局约束
         │ 阶段 1 完成，拿到真实文件结构/依赖/核心逻辑
         ▼
阶段 2 细拆（读取 H1 前序产出 + G 域，仅当前阶段有效）
  [方案设计] ── 文件级子任务 DAG（A 模块改造 / B 接口对接 / ...）
        │ 阶段结束 → 归档进 P 域（§2.5 P1），阶段骨架仅 status 前移
        ▼
阶段 3 细拆 ... （复用前两阶段真实产出，不臆测文件列表）
```

- 细拆输入：**H1 已完成阶段产出 + G 域阶段骨架**，不读 S 域中间推理（§2.5 规则 4）。
- 细拆调用：`call_scene=plan-phase`（轻量模型，N 次/任务，控成本），全局粗规划走 `call_scene=plan`（强模型，1 次/任务，保质量）——同一 `HarnessPlanner`，仅调用点分层（phase5 §5.3）。
- 细拆产物：仅当前阶段有效，阶段结束即归档（`Phase.status=archived` + 阶段细节从 H1 压缩进 P 域）；阶段骨架字节不变 → Tier 1 幂等 upsert（§2.3.4 H1 拆分）。
- 阶段切换 = 天然的重规划边界：信息缺口（成功但发现新依赖）在阶段完成时自然驱动下一阶段细拆，无需额外触发。

#### 4.1.2 INCREMENTAL：步进式单步规划（兜底）

> **v9 S5：本小节删除**——open 场景（未知根因故障排查、陌生领域调研）直接走既有 ReAct（`planMode=none`）。ReAct 已含 spawn/taskboard/沙箱，能力面覆盖步进式探索；不进 harness。

### 4.2 S2: Validate — 目标对齐校验

复用 `PlanValidator` + `GoalAlignmentValidator`（语义去重）。校验失败 → replan（最多 2 次）。

### 4.3 S3: Execute — 工具调用 Worker

```java
// 核心执行路径——Chat 和 Task 模式共用
// Worker = Planner 的工具调用（forWorker() 丰富上下文）
// Worker 内部可 spawn 真正隔离的子 Agent（forSubAgent=empty()）

private void executeTasks(PlanNotebook notebook) {
    while (notebook.hasMoreTasks() && !notebook.shouldStop()) {
        TaskItem task = notebook.nextTask().get();
        // 工具调用 Worker：类似 Planner 调了一次 tool
        TaskResult result = invokeWorker(task, notebook);
        // v9 S1：无独立 Evaluator——Chat/Task 统一 Planner 自判
        notebook.setGoalCompletion(planner.selfAssess(task, notebook));
        if (result == TaskResult.PASS) continue;
        else if (result == TaskResult.RETRY) notebook.requeueTask(task);
        else replanFailedTask(notebook, task);
    }
    // 全部完成 → Planner 综合回答
    planner.finishAnswer(notebook);
}
```

**Worker 上下文（`AssembledContext.forWorker()`）**：

Worker 不是隔离子 Agent。它是 Planner 的工具调用，需要**清晰详细的目标 + 充分的上下文**避免跑偏：

```java
// Worker 的 AssembledContext 构造（v10 定稿：不注入 L2 用户画像）
AssembledContext.forWorker(
    // 无 L1 对话历史（Worker 不参与和用户的多轮对话）
    // 无 L2 用户画像（v10：任务执行器只需任务契约，见 §2.4 注记）
    workerPrompt: {
        taskGoal:        "收集 A/B/C 三家竞品的定价信息",
        upstreamResults: {
            "info-collector": "已获取 A/B 竞品定价，C 竞品信息缺失，需补充获取渠道"
        },
        constraints:      "使用 search_web 工具，不要用 sandbox_exec",
        expectedOutput:   "结构化 JSON: {competitor, pricing, source}",
        successCriteria:  "三家竞品均有完整定价数据 → PASS"
    },
    systemOverlay: Planner 注入的任务级 system prompt,
    tools: [search_web, ...]  // 工具白名单
)
```

> **v6 注记（upstream 读取方式）**：上例 `upstreamResults` 在实现上取自 `plan_shared_memory` 共享快照（§2.4），仅渲染**当前 task 依赖的上游结果**（按 `dependsOn`），而非每 worker 全量注入全部上游——保证跨 worker 稳定前缀复用、各 worker 只 miss 自己的动态段。

**Worker 内部子 Agent（`AssembledContext.forSubAgent()=empty()`）**：

Worker 内部可通过 `spawn_subagent` 启动真正隔离的子 Agent——这是 nested spawn。子 Agent 仅接收 spawn prompt（任务描述 + 输入），输出结论，上下文完全隔离。

```
工具调用 Worker("收集竞品信息")
  │
  ├── think: 需要搜索 A 公司定价
  ├── tool: search_web("A company pricing 2026")
  ├── observe: A 定价 $99/月
  │
  ├── think: C 公司数据难找，启动一个子 Agent 专门搜索
  ├── spawn_subagent("搜索 C 公司定价...")  ← forSubAgent=empty()
  │    └── 子 Agent 隔离执行 → 回报结果
  │
  ├── think: 汇总 A/B/C 定价
  └── handoff → Planner
```

**Worker handoff 双写（进入 Planner 上下文）**：

Worker 完成的 handoff 同时做两件事：
1. **写 H1 PlanNotebook**（§2.4 共享工作记忆）：持久化 task 结果、goal 进度，供后续轮次 / follow-up 读取。
2. **追加 Planner L1 尾部**：作为工具结果（`observe`）append 在消息末尾，**与普通 ReAct 的 `tool_result` 一视同仁**（C2 尾部增量）：

```
Planner L1 上下文（单轮 ReAct run）:
  ┌──────────────────────────────────────────────────┐
  │ Tier 0/1 稳定前缀（tools + base + L2 + Far/Mid）  │ ← 字节不变，KV 命中
  │ Tier 2 尾部：H1 PlanNotebook（压缩点前移机制）     │
  │ user: "帮我写一份竞品分析报告"                     │
  │ planner: think(规划：收集信息→分析→撰写)           │
  │ planner: tool(Worker("收集竞品信息", ctx))        │ ← 视同 tool_call
  │ worker-1: observe(handoff: 三家定价已获取...)      │ ← 视同 tool_result，tail append
  │ planner: think(信息收集完成，开始分析)              │
  │ planner: tool(Worker("分析竞品优劣势", ctx))       │
  │ worker-2: observe(handoff: A优势xx/B弱势xx...)    │
  │ planner: think(分析完成，综合回答)                 │
  └──────────────────────────────────────────────────┘
  压缩时：Worker handoff 参与 L1 压缩点前进（旧 handoff 折叠进 Far，不重排 Near/Mid）
```

**并行执行**：Chat 和 Task 模式均支持并行工具调用 Worker。Full 模式按 `dependsOn` 约束并行化独立 task。Worker 内部也可并行 spawn 多个子 Agent。

### 4.4 S4: Evaluate — ~~Chat 模式专用~~ v9 S1：砍独立 Evaluator

> **v9 S1 覆盖**：独立 `TaskEvaluator` / `GoalEvaluator` **不实现**。Chat/Task 统一由 Planner `selfAssess`（0~1 分 + 简短理由）决策。真实代价与兜底见 [rebuild spec §0.1 S1](../2026-08-05-planner-executor-rebuild-design.md#01-简化决议v2--2026-08-05)。

| ~~评估器~~ | ~~粒度~~ | ~~问题~~ | ~~使用模式~~ |
|--------|------|------|:---:|
| ~~TaskEvaluator~~ | ~~单个 task~~ | ~~"task 目标达成了吗？"~~ | ~~Chat (Full)~~ |
| ~~GoalEvaluator~~ | ~~全局目标~~ | ~~"离原始目标还有多远？"~~ | ~~Chat (Incremental + Full 收尾)~~ |

**Task 模式**：无独立 Evaluator。Worker 的 handoff 包含「做了什么、遇到什么问题、有什么建议」——评估信息自包含。Planner 基于 handoff 直接决策。

> **v6 注记（Evaluator 结果落库）**：Chat 模式 `TaskEvaluator`/`GoalEvaluator` 的 task PASS/FAIL 结果**写入 `harness_eval_result`**（`run_id` + task + PASS/FAIL + reason），供 [phase5 §5.1](../phase5-operation-openness-design.md) 效果看板按 run 聚合 task 级成功率。字段约定见 phase5 §5.1 v2 注记——Evaluator 结果当前不落库将导致 harness 效果完全不可见。

**Maker-Checker 分离**：执行 task 的 Worker 和评估 task 的 Evaluator 是不同 LLM 调用、不同 prompt，确保评估不受执行过程认知偏差影响。

### 4.5 Answer 合并到 Planner

Planner 已有 L1(query) + H1(全部执行历史)，完全有能力综合回答。**不设独立 Synthesizer**。

```java
// Planner.finishAnswer():
// 当评估信号为 done 时，Planner 直接流式输出综合回答
// 输入：originalGoal + notebook.renderForPlanner()
// 输出：SSE step_delta(result) 流
```

---

## 5. 持久化、故障转移与自愈

Planner-Worker Loop 是长任务执行模式（多轮 Planner → Worker → Evaluate 循环，单次会话可达 5 轮、10+ Worker 调用）。需要保证以下场景可恢复：

| 场景 | 丢失内容 | 影响 |
|------|---------|------|
| orchestrator 重启 | 内存中的 PlanNotebook + 当前 Worker handle | 已完成的 task 丢失记录，运行中的 Worker 成为孤进程 |
| Worker 超时/崩溃 | Worker 内部 ReAct scratchpad + 待 handoff 结果 | task 无产出，Planner 无线索做下轮规划 |
| Planner LLM 超时 | 本轮规划结果 | 无法进入本轮 Execute |
| Evaluator LLM 超时（Chat） | 本轮评估分数 | Planner 缺乏评估信号，无法决定继续/完成 |
| 会话过期（TTL） | 所有 H1 状态 | 用户需重新开始 |

### 5.1 持久化：Checkpoint / Restore

#### 5.1.1 PlanNotebookStore（Redis + MySQL 双写）

> **v9 S2 覆盖**：本节双写/多 key/MySQL 表/version 重放 **不实现**。落地为 **Redis 单写**：`planner:notebook:{sessionId}` → PlanNotebook JSON（每轮结束整体 save 一次），TTL 7d，delete/renewTtl。冷审计由既有 `PlanExecutionAuditService`（RocketMQ/MySQL/ES）覆盖。以下保留作设计演进记录。

```
写路径：每次状态变更 → Redis SET（原子，覆盖写）+ 异步 MySQL INSERT/UPDATE
读路径：会话启动 → Redis GET → 未命中 → MySQL SELECT → 重建 Redis
```

| 存储 | 用途 | TTL |
|------|------|-----|
| **Redis** | 热状态，Planner 实时读写 | Chat: 24h / Task: 7d |
| **MySQL** | 冷审计，崩溃恢复底线，一次 Loop 结束后落库 | 永久 |

**Redis Key 规范**：
```
planner:notebook:{sessionId}     → PlanNotebook JSON（当前 snapshot）
planner:round:{sessionId}:{n}    → RoundRecord JSON（逐轮记录，供 Mid/Far 压缩查询）
planner:worker:{sessionId}:{id}  → Worker runId（C2 写入，仅用于 orchestrator 重启后定位 Worker）
```

**MySQL 表**（`planner_notebooks`）：
```sql
CREATE TABLE planner_notebooks (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id      VARCHAR(64)  NOT NULL,
    original_goal   TEXT         NOT NULL,
    scene           VARCHAR(16)  NOT NULL DEFAULT 'chat',
    task_decomp     VARCHAR(16)  NOT NULL DEFAULT 'incremental',
    total_rounds    INT          NOT NULL DEFAULT 0,
    total_tasks     INT          NOT NULL DEFAULT 0,
    stale_rounds    INT          NOT NULL DEFAULT 0,
    goal_completion DECIMAL(3,2) NOT NULL DEFAULT 0.00,
    state_json      MEDIUMTEXT   NOT NULL COMMENT 'PlanNotebook 完整快照 JSON',
    version         INT          NOT NULL DEFAULT 1,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_session (session_id)
);
```

#### 5.1.2 Checkpoint 时机

```
 Planner-Worker Loop 单轮执行中 4 个强制 checkpoint：

 ┌──────────┐     ┌───────────┐     ┌───────────┐     ┌───────────┐
 │ [C1]     │     │ [C2]      │     │ [C3]      │     │ [C4]      │
 │ 规划完成  │ ──→ │ Worker     │ ──→ │ 评估完成   │ ──→ │ 下一轮     │
 │          │     │ 启动前     │     │           │     │ 开始前     │
 └──────────┘     └───────────┘     └───────────┘     └───────────┘
  task = PENDING    task = IN_PROG    task = DONE       round++
  ver++             ver++             ver++             ver++
```

| Checkpoint | 触发时机 | 写什么 |
|:---:|------|------|
| **C1** | Planner 完成本轮规划，taskQueue 更新 | `RoundRecord`（plan 内容 + task 列表）→ MySQL；Notebook 完整 snapshot → Redis |
| **C2** | Worker 工具调用启动前 | task 状态=`IN_PROGRESS`，Worker `runId` → Redis（仅用于 orchestrator 重启后定位 Worker） |
| **C3** | Worker handoff 回传 / Evaluator 打分完成 | task 状态=`DONE/FAIL`，handoff/EvalResult → Redis；`RoundRecord` 补写 → MySQL |
| **C4** | 本轮结束，准备进入下一轮 | Notebook 全局字段（goalCompletion, staleRounds, nextDirection）→ Redis + MySQL |

**Worker 内部持久化**：Worker 是 ReAct Agent，其 think/tool 循环状态、中间结果和最终 handoff 由 **AgentScope 2.0 原生 `StateStore`**（`agentscope:state:{runId}`，TTL=7d）自动管理——包括 Checkpoint、崩溃恢复、优雅停机。**Planner 不需要重复持久化 Worker 内部状态**。

**Worker 故障转移**：AgentScope 2.0 原生支持 `disableSessionPersistence()` 已废弃，自 2.0 起自动持久化 + JVM 优雅停机（`GracefulShutdownManager`）。Worker 崩溃后重启时，AgentScope 自动从 StateStore 恢复 ReAct 循环状态并继续执行← Planner 只管超时等待。

#### 5.1.3 崩溃恢复流程

```
orchestrator 重启 / 会话重连
          │
          ▼
  Redis GET planner:notebook:{sessionId}
          │
    ┌─────┴─────┐
    ▼           ▼
  命中         未命中
    │           │
    │           └──→ MySQL SELECT → 反序列化 PlanNotebook → 回写 Redis
    │
    ▼
  检查 taskQueue 中所有 task 状态
          │
    ┌─────┼─────┐
    ▼     ▼     ▼
  DONE  IN_PROGRESS  PENDING
    │     │           │
    │     ├──→ 从 AgentScope StateStore 查
    │     │    （`agentscope:state:{runId}`）
    │     │      ├── 有 completed 记录 → 恢复 handoff，标记 DONE
    │     │      └── 无记录（Worker 也挂了）→ 标记 FAIL，下一轮 replan
    │     │
    │     └──→ (无 IN_PROGRESS task) 正常继续
    │
    ▼
  resumeLoop() — 从当前 round / taskQueue 位置继续
```

**恢复原则**：
- **已完成的不重做**：task=DONE 的不回放
- **进行中的查 Worker**：先查 Worker 死活，再决定等/回收
- **未开始的继续**：PENDING 的 task 正常执行
- **恢复超时**：若恢复过程自身超过 30s → 降级为「直接综合回答」

#### 5.1.4 PlanNotebookStore 接口

```java
public interface PlanNotebookStore {
    /** 保存完整 snapshot 到 Redis，异步写 MySQL（仅 C1/C4 写 MySQL） */
    void save(PlanNotebook notebook, CheckpointReason reason);

    /** 从 Redis/MySQL 读取（优先 Redis） */
    Optional<PlanNotebook> load(String sessionId);

    /** 更新单个 task 状态（C2/C3 用） */
    void updateTaskStatus(String sessionId, String taskId, String status);

    /** 补写 RoundRecord（C3 用） */
    void updateRoundRecord(String sessionId, int roundIndex, RoundRecord record);

    /** 删除会话（Loop 正常结束或过期后调用） */
    void delete(String sessionId);

    /** 续约 Redis TTL */
    void renewTtl(String sessionId);

    enum CheckpointReason { C1_PLAN, C2_WORKER_START, C3_EVALUATE, C4_ROUND_END, HEARTBEAT }
}
```

---

### 5.2 故障转移

#### 5.2.1 Worker 故障

**AgentScope 2.0 原生能力**：Worker 内部 ReAct 循环的 Checkpoint、崩溃恢复、优雅停机由 AgentScope 2.0 `StateStore`（`agentscope:state:{runId}`，TTL=7d）自动管理。Worker 进程崩溃后，AgentScope 自动从 StateStore 恢复状态继续执行。**Planner 不需要管理 Worker 内部故障转移**。

Planner 层面只需处理以下 Worker 不可用场景：

| 故障 | 检测 | 恢复 |
|------|------|------|
| Worker 超时（120s） | `PlannerHarnessLoop` 定时器 | 标记 task=FAIL；若 `taskRetry < max` → 重试同一 task（新 runId）；否则 → Planner replan |
| Worker 异常（AgentScope 抛不可恢复异常） | `AgentRuntimeException` | 同超时处理；AgentScope 已尝试内部恢复，到达 Planner 层面说明不可恢复 |
| Worker handoff 格式错误 | handoff 解析异常 | 标记 task=FAIL；Planner 可根据错误信息调整下轮 prompt |
| Worker 内部 spawn 的子 Agent 失败 | Worker 的 ReAct 循环内 `FailureBudgetMiddleware` | Worker 自行处理（降级、跳过该子任务）；**不上升到 Planner** |

**重试策略**：

```java
// PlannerHarnessLoop 内：
int retries = 0;
while (retries <= maxTaskRetries) {
    try {
        taskResult = invokeWorker(task, notebook);
        if (taskResult == TaskResult.PASS) break;
        if (taskResult == TaskResult.RETRY && retries < maxTaskRetries) {
            retries++;
            continue;  // 同 task、同上下文重试
        }
        // FAIL 或重试耗尽 → 交 Planner replan
        planner.replanFailedTask(notebook, task);
        break;
    } catch (TimeoutException | AgentRuntimeException e) {
        retries++;
        if (retries > maxTaskRetries) {
            taskResult = TaskResult.FAIL;
            break;
        }
    }
}
```

#### 5.2.2 触发式重规划（执行中修正计划偏差）

**重规划触发条件**（Executor 监控，命中任意一条 → 交 Planner 重规划；触发本身机械可量化，不做推理）：

| # | 触发 | 检测 | 响应 |
|---|------|------|------|
| 1 | **连续失败** | 单 task 重试耗尽（`taskRetryMax`） | replan 当前失败 task |
| 2 | **信息缺口** | 执行成功但 handoff 暴露原计划未覆盖的依赖/约束 | replan 当前阶段剩余任务（HIERARCHICAL 下阶段完成自然驱动下一阶段细拆） |
| 3 | **目标变更** | 用户 follow-up 更新 `H1.originalGoal`（§2.3.5） | 标记受影响 task obsolete → replan 剩余 |
| 4 | **进度偏差** | `GoalAlignmentValidator` 每轮校验 + `staleRounds≥2`（Stuck） | 方向偏离则修正计划；Stuck 强制综合 |
| 5 | **资源溢出** | Token/时长接近预算（`max-rounds`/`max-total-tasks`/`max-duration-ms`） | 拆分或简化当前阶段任务，而非直接停止 |

**重规划边界规则**（避免失控）：

1. **保留成果**：已完成 task 标记幂等（Checkpoint + `version` 重放，§5.1），重规划只调整未执行部分，**不重复执行已完成任务**。
2. **局部修正**：优先只调整当前阶段计划；**不轻易修改全局阶段骨架**（阶段划分变更需目标变更/信息缺口重大证据，且写回 G 域触发 Tier 1 一次失效）。
3. **上下文隔离**：重规划只读取**全局目标 + 已执行结果**（G 域 + P1），**不读取 S 域内部推理过程**（§2.5 规则 4）。
4. **收敛控制**：单阶段重规划上限 `max-phase-replans=3`；计划相似度校验（规则化：新 task label 与旧重叠率 > 阈值即视为无效重规划）；总时长熔断 `max-duration-ms`，耗尽 → 综合已收集结果回答。
5. **写隔离**：重规划**不回滚已完成文件修改**——同 checkout 内已完成文件不动（task-workspace-codex §2.3：无应用层锁，隔离/回滚语义交给 Git + 用户显式 checkout）。

> **编码场景注记**：调研中的「文件级锁与快照」在本项目等价实现为 **checkout（主分支/worktree）隔离 + Git 语义**（[task-workspace-codex](./2026-07-28-task-workspace-codex-design.md) §2.3 否决应用层读写锁）。「重规划不回滚已完成修改」的目标由 checkout 承载：并行 Worker 改不同模块天然隔离，同 checkout 内已完成文件不动。

#### 5.2.2 Planner LLM 调用故障

```
Planner LLM 调用失败
  ├── 网络/网关超时（60s）→ 重试（max 2 次），指数退避 2s → 4s
  ├── 429 限流 → 退避 10s 后重试
  ├── 5xx 服务端错误 → 退避 5s 后重试
  └── 2 次重试耗尽
        ├── 有已收集的 Worker 结果 → 降级：直接综合回答
        └── 无任何结果（首轮即失败）→ 降级：走 ReactExecutor 兜底
```

Planner 故障时 **PlanNotebook 已经 Checkpoint 过（C1）**，状态不丢失。恢复时从 C1 之后继续。

#### 5.2.3 Evaluator 故障（Chat 模式专用）

```
Evaluator LLM 调用失败
  ├── 超时（30s）→ 不回退 Planner，因为 Planner 和 Evaluator 是独立调用
  └── 失败后 → 降级为 Task 模式行为：
        本轮 goalCompletion 由 Planner 自判（planner.selfAssess()）
        日志标记 "evaluator_fallback:planner_self_assess"
        后续轮次恢复 Evaluator
```

**不降级整体**：单轮 Evaluator 故障不影响 Loop 继续。下一轮重新启用 Evaluator。

#### 5.2.4 基础设施故障

| 组件 | 故障 | 处理 |
|------|------|------|
| Redis 不可用 | PlanNotebookStore 读写失败 | 降级为**内存模式**（仅本次 Loop，不可恢复）；Loop 结束时写 MySQL |
| MySQL 不可用 | 异步写入失败 | 不阻塞主流程；后台重试队列 + 日志告警 |
| LLM Gateway 不可用 | 所有 LLM 调用失败 | 所有降级路径聚合 → 用户可见错误 SSE |
| Milvus 不可用 | L2 embedding 召回失败 | 返回空候选列表 → L3 深层语义兜底 |

---

### 5.3 自愈

#### 5.3.1 Stuck Detection（卡死检测）

```
每一轮结束时检测：
  if (goalCompletion 与上一轮相比 delta < 0.05) {
      staleRounds++;
  } else {
      staleRounds = 0;
  }

  if (staleRounds >= staleRoundsThreshold) {
      → 强制 Planner 综合回答（无视 goalCompletion）
      → SSE 通知用户 "连续 {n} 轮无实质进展，已汇总当前信息"
  }
```

**Stuck 根因分类**（仅供运维排查，不自动处理）：
- Planner 产生循环任务（A→B→A→B...）
- Worker 持续产出低质量结果（Evaluator 连续低分）
- 任务本身超出 Agent 能力

#### 5.3.2 Orphan Worker 清理

```
场景：Planner 启动 Worker → orchestrator 进程崩溃/重启 → Worker 仍在运行

恢复流程：
  1. 重启后 load(sessionId) 发现 task=IN_PROGRESS
  2. 通过 C2 记录的 runId 查询 AgentScope StateStore：
     （`agentscope:state:{runId}`）
  3. 若 Worker 已完成（StateStore 有 completed 记录）：
      - 从 StateStore 读取 Worker 的最终 handoff
      - 恢复 handoff 到 PlanNotebook，标记 task=DONE
      - 继续执行后续 task
  4. 若 Worker 无记录（Worker 进程也挂了且 AgentScope 未完成 Checkpoint）：
      - 标记 task=FAIL → Planner replan
```

**AgentScope 2.0 已处理的部分**：Worker 心跳、优雅停机、内部状态 Checkpoint 均由 AgentScope `StateStore` + `GracefulShutdownManager` 自动管理。**Planner 不重复实现心跳**——仅通过查询 StateStore 判断 Worker 是否已完成。

#### 5.3.3 幂等重放

PlanNotebook 的 `version` 自增计数器确保即使同一条恢复逻辑被多次触发，也不会重复写入：

```java
public void save(PlanNotebook notebook, CheckpointReason reason) {
    int currentVersion = redis.get("planner:notebook:" + sessionId + ":version");
    if (notebook.getVersion() <= currentVersion) {
        return;  // 已写入，跳过
    }
    redis.set("planner:notebook:" + sessionId, notebook.toJson());
    redis.set("planner:notebook:" + sessionId + ":version", notebook.getVersion());
    // 异步 MySQL
}
```

#### 5.3.4 Idle GC（空闲回收）

| 场景 | 策略 |
|------|------|
| 用户在 Planner-Worker 执行中长时间不响应（如中途关闭页面） | Redis TTL 到期 → 下次 GC 扫描清理 |
| 用户发 follow-up 但上一轮 Worker 还在跑 | 新消息正常进入 Planner → Planner 根据消息内容决定中断 / 等待 / redirect |
| 会话级超时（30min 无活动） | SSE 下发 `type:session_expired` → 前端提示"会话超时，可重新开始" |

---

### 5.4 安全边界（阈值与降级汇总）

| 边界 | 默认值 | 说明 |
|------|:-----:|------|
| `maxRounds` | 5 | 硬顶（全模式；Incremental 步进轮数 / Hierarchical 阶段内轮数） |
| `maxTotalTasks` | 10 | Full 模式 task 数硬顶 |
| `maxPhaseReplans` | 3 | 单阶段重规划上限（v8，收敛控制） |
| `taskRetryMax` | 1 | 单 task Worker 调用最大重试（含超时/崩溃） |
| `plannerRetryMax` | 2 | Planner LLM 调用最大重试 |
| `maxReplanAttempts` | 2 | Planner replan 次数（不同于 LLM retry） |
| `staleRoundsThreshold` | 2 | 连续无进展轮次 → 强制回答 |
| `plannerTimeoutMs` | 60s | Planner LLM 调用超时 |
| `evaluatorTimeoutMs` | 30s | 评估器超时（Chat 专用，超时降级 Planner 自判） |
| `workerTimeoutMs` | 120s | Worker 工具调用超时（比普通工具长） |
| `workerMaxSubAgents` | 3 | Worker 内部可 spawn 的子 Agent 数上限 |
| `recoveryTimeoutMs` | 30000 | 崩溃恢复最大耗时 |
| `sessionIdleTimeoutMs` | 1800000 | 会话无活动超时（30min） |
| `checkpointVersionGap` | 3 | 版本号跳过超过此值 → 告警 |

**降级路径总览**：

```
Worker TIMEOUT → taskRetryMax 次重试 → FAIL → Planner replan
Worker CRASH  → Orphan 恢复 → 有结果→复用 / 无结果→FAIL → replan
Planner TIMEOUT → plannerRetryMax 次重试 → 有已收集结果 → 降级综合回答
                 └── 无结果 → 降级 ReactExecutor
Evaluator TIMEOUT（Chat） → 降级 Planner 自判本轮 → 下轮恢复 Evaluator
Replan 耗尽 → Planner 综合已收集信息回答
Chat: goalCompletion < 0.4 连续 2 轮 → Planner 降级回答
Task: Planner 自判完成 → 回答
Stale ≥ 2 轮 → 强制综合回答
任意阶段 maxRounds 耗尽 → Planner 回答
Redis 不可用 → 内存模式（不可恢复）→ Loop 结束时兜底写 MySQL
LLM Gateway 全挂 → SSE 错误 → 用户可重试
```

---

## 6. 与 ReAct Harness Loop（4.7.7/4.7.8）的协同

分层互补：

| Planner-Worker Loop（本文） | ReAct Harness Loop（4.7.7/4.7.8） |
|---|---|
| 顶层决策层 | 执行自律层 |
| Planner 显式规划（ReAct MAIN think） | Worker 内部的 ReAct 循环 |
| Evaluator / Planner 自判 | `GoalAlignmentMiddleware` / `CompletionGuardMiddleware` |
| 引擎级 task 重试 | `FailureBudgetMiddleware` |

Worker 通过 `forWorker()` 构建上下文，内部 ReAct 循环天然包含完整 Middleware 链（4.7.7/4.7.8）。Planner 自身作为 ReAct MAIN 也受益于 Middleware（目标对齐 / 失败预算），但顶层已有显式规划+评估，不依赖 4.7.7 的 GoalAlignment。Worker 内部 spawn 的子 Agent（`forSubAgent=empty()`）同样走完整 Middleware 链。

Planner 的记忆压缩流程：L1 上下文超过预算时，Worker handoff 视同 tool_result 参与 L1→L2 压缩（与普通 ReAct MAIN 一致）。H1 PlanNotebook 独立走 Near/Mid/Far 窗口压缩。

---

## 7. Timeline 约定

### 7.1 主 Timeline 形态

**Full 模式**：
```
intent → plan(R1,full) → task-1(+eval) → task-2(+eval) → ... → planner-answer
```

**Incremental 模式**：
```
intent → plan(R1,inc) → worker → think → plan(R2) → worker → ... → planner-answer
```

### 7.2 各步骤约定

| 步骤 | 来源 | 说明 |
|------|------|------|
| `intent` | 路由层 | 同现约 |
| `plan(R{n},{mode})` | PlanTimeline | 含本轮规划意图 + 分解模式 |
| `task-{id}` | Harness 新增 | Full 模式：task 容器步，`subSteps` 含 Worker 执行记录 |
| `think` | Planner | Incremental 轮次间反思 |
| `planner-answer` | Planner | 流式综合回答（合并原 Synthesizer） |

### 7.3 前端复用

- `PlanExecutionCanvas`：Full 模式 Task Tree + 逐 task 高亮当前执行
- `PlanNodeDrawer`：节点记录（复用）
- `PlanWorkflowPanel`：整体进度视图
- `PlanApprovalActions`：**已随动态 Plan-Workflow 删除**（[planner-executor-rebuild D5](../2026-08-05-planner-executor-rebuild-design.md)）；Harness 无用户确认步（渐进式/自驱）

---

## 8. 组件清单

### 8.1 新建清单

| 组件 | 说明 |
|------|------|
| `PlannerHarnessLoop` | S1-S3 双模编排引擎（chat/task 自动切换），Planner 是 ReAct MAIN（全量 L1+L2+H1），Worker 是其工具调用。内置 Checkpoint 控制、超时/重试、Stuck 检测 |
| `PlanNotebook` | 跨轮共享工作记忆 POJO (H1)，叠加在 Planner L1+L2 之上。含 `version` 自增计数器支持幂等重放 |
| `PlanNotebookStore` | Redis + MySQL 双写（Checkpoint C1-C4 驱动）。支持崩溃恢复、Orphan Worker 清理、Idempotent replay |
| `PlanNotebookRecoveryService` | 崩溃恢复入口：load Notebook → 检查 task 状态 → 查 AgentScope StateStore 回收 handoff → resumeloop() |
| `HarnessPlanner` | 双模 Planner：规划 + 决策 + 综合回答（复刻普通 ReAct MAIN 的记忆模型）。三态分解自判（FULL/HIERARCHICAL/INCREMENTAL，§0.2）+ 阶段细拆（`planner.phase`，§4.1.1）+ 触发式重规划（§5.2.2） |
| `WorkerContextFactory` | 构造 `AssembledContext.forWorker()`：**稳定前缀**（Tier 0 + 任务目标 + `plan_shared_memory` 共享快照，跨 worker 复用）+ toolWhitelist + query；upstreamResults 改由共享区按需读取（§2.4） |
| `PlanSharedMemoryStore` | **P2 域载体**（§2.5.1）：按 `dependsOn` 定向传递的任务产出存储（Redis），只读注入 S 动态段、S 域失败闭环、run 结束清理 |
| `GoalEvaluator` | LLM 全局目标评估器（Chat 专用） |
| `TaskEvaluator` | LLM 逐 task 评估器（Chat Full 专用） |
| `GoalAlignmentValidator` | 目标对齐校验 |
| `PlannerHarnessExecutor` | ResourceDispatcher 入口，根据 scene 决定 Chat/Task 模式 |
| `TaskResult` | 单 task 结果枚举（PASS/RETRY/FAIL） |

### 8.2 不动清单

| 组件 | 原因 |
|------|------|
| `PlanWorkflowExecutor` | planMode=oneshot 时使用 |
| `ReactExecutor` | planMode=none 时使用（Harness 模式不走 ReactExecutor） |
| `WorkflowExecutor` | Harness Loop 不调用 |
| `PlanValidator` | 不改动 |
| `AgentRuntime` | 复用 `AgentRunRequest.forWorker()` + `AgentRunRequest.forSubAgent()` |
| `SpawnSubagentTool` | Worker 内部通过此工具 spawn 隔离的子 Agent |

### 8.3 不需要的组件

| 组件 | 原因 |
|------|------|
| `Synthesizer` | 合并到 Planner——Planner 已有 L1+L2+H1 |

---

## 9. 实施阶段

| 阶段 | 内容 | 出口 |
|:---:|------|------|
| H-0 | `planner_notebooks` DDL + `PlanNotebook` POJO + `PlanNotebookStore` 接口 | 单测绿 |
| H-1 | `PlanNotebookStore` Redis+MySQL 实现（C1-C4 持久化 + 幂等 replay） | 单测（save→load→checkpoint 一致性） |
| H-2 | `PlanNotebookRecoveryService`（崩溃恢复 + 查 AgentScope StateStore 回收 Worker handoff） | 单测（模拟 crash→恢复→状态一致） |
| H-3 | `HarnessPlanner` + `GoalAlignmentValidator` | 单测（Full/Hierarchical/Incremental 三态输出） |
| H-4 | `TaskEvaluator` + `GoalEvaluator` + `PlannerHarnessLoop`（含 Checkpoint + 超时/重试/Stuck/重规划逻辑） | 单测（双模编排 + 评估 + 故障模拟） |
| H-5 | `PlannerHarnessExecutor` + v3 RoutingResult 扩展 | 路由正确 |
| H-6 | 前端 + Timeline | 视觉验收 |
| H-7 | Live 验收（含崩溃恢复/超时重试验收） | 回归门禁 |

---

## 10. Catalog / Prompt 清单

| Catalog ID | 用途 | 模式 |
|-----------|------|:---:|
| `planner.harness` | Planner system prompt（规划+两态分解+重规划+自判+综合，含 Worker 工具调用说明） | 共用 |
| `planner.phase` | 阶段细拆 prompt（HIERARCHICAL，读取 H1 前序产出 + G 域，输出当前阶段 task 列表） | Hierarchical |
| ~~`harness.task-evaluator`~~ | **v9 S1 删除**：独立评估器不实现，Planner `selfAssess` 统一决策 | — |
| ~~`harness.goal-evaluator`~~ | **v9 S1 删除**：同上 | — |
| `harness.worker` | Worker 的 system prompt（forWorker 上下文模板） | 共用 |
| L3 分类器 prompt 补充 | planMode 输出规则（scene 来自用户选择，作为 L3 输入参数） | 共用 |

---

## 11. Nacos 配置

```yaml
agent:
  execution:
    harness:
      enabled: false
      # scene 来自用户选择（前端传入），不为 Nacos 静态配置
      # scene=chat → Chat 模式（含 Evaluator）
      # scene=task → Task 模式（Planner 自判）
      max-rounds: 5
      max-total-tasks: 10
      max-duration-ms: 600000       # v8：总时长熔断（资源溢出触发 → 拆分/简化任务，非直接停止）
      stale-rounds-threshold: 2
      task:
        max-retries: 1
      planner:
        timeout-ms: 60000
        max-attempts: 2          # LLM 调用最大重试（不同于 replan-次数）
        max-phase-replans: 3     # v8：单阶段重规划上限（收敛控制）
        plan-similarity-threshold: 0.8  # v8：新 task label 与旧重叠率 > 此值 → 无效重规划
      evaluator:                  # Chat 专用（scene=chat 时有效）
        timeout-ms: 30000
        goal-threshold: 0.9
      worker:                     # Worker 工具调用配置
        timeout-ms: 120000        # Worker 内部 ReAct 超时（比普通工具调用长）
        max-sub-agents: 3         # Worker 内部可 spawn 的子 Agent 数上限
        # 注意：Worker 心跳、内部 Checkpoint、崩溃恢复由 AgentScope 2.0 StateStore 自动管理
        # 相关配置在 sunshine-agent.yaml 的 agentscope.state 段
      notebook:
        redis-ttl-seconds: 86400  # Chat: 24h, Task: 7d
        compression:              # H1 注入块内部两级（v10 定稿，§2.3.4）：阶段骨架 + 近 near-keep-rounds 轮原文，
          near-keep-rounds: 6     #   超阈值最老轮次 LLM 折叠为摘要；无 last_folded_round 压缩点；与 L1 压缩窗口无关
      checkpoint:
        mysql-async: true         # MySQL 异步写入
        mysql-retry-max: 3        # MySQL 写入失败重试次数
        version-gap-alert: 3      # 版本号跳跃超过此值 → 告警
      recovery:
        timeout-ms: 30000         # 崩溃恢复最大耗时
      session:
        idle-timeout-ms: 1800000  # 30min 无活动 → 过期
```

---

## 12. 验收标准

### 12.1 单测

| 用例 | 预期 |
|------|------|
| PlanNotebook scene 切换 | `isChat()` / `isTask()` 正确 |
| WorkerContextFactory 构造 | forWorker 上下文含 taskGoal + upstreamResults + constraints + toolWhitelist |
| Worker 内部 spawn 子 Agent | 子 Agent `forSubAgent=empty()`，仅 spawn prompt → 输出 |
| HarnessPlanner 三态输出 | 结构清晰→closed/full；复杂任务→phase-closed/hierarchical；高不确定→open/incremental |
| HarnessPlanner Hierarchical | 复杂任务→phase-closed；阶段骨架 3~5 个 + 依赖 + 全局约束，无虚构 task |
| 阶段细拆（`planner.phase`） | 到达阶段 2 时仅拆当前阶段 task DAG，读取 H1 前序产出，不臆测未到阶段 |
| 触发式重规划局部修正 | 信息缺口 → 仅 replan 当前阶段，阶段骨架不变，已完成 task 幂等跳过 |
| 重规划收敛控制 | `max-phase-replans=3` 触发后强制综合；task label 重叠率 > 阈值 → 无效重规划丢弃 |
| HarnessPlanner 综合回答 | 输入 L1+H1 后正确综合回答 |
| Worker handoff 回流 Planner L1 | handoff 以 tool_result 形态进入 L1，可被 Planner 读取 |
| TaskEvaluator PASS/RETRY/FAIL | 各场景正确 |
| GoalEvaluator 全局打分 | 高分/低分场景正确 |
| Chat 模式完整执行（scene=chat） | 3 轮 Planner→Worker(工具调用)→Evaluator→Planner 综合 |
| Task 模式完整执行（scene=task） | Planner→Worker(工具调用)→handoff→Planner 自判→综合 |
| 并行 Worker 工具调用 | 3 个 Worker 并行执行正确 |
| Planner L1 上下文压缩 | Worker handoff 参与 L1→L2 压缩，同 tool_result |
| H1 两级折叠（v10 定稿） | 注入块近 `near-keep-rounds` 轮原文；超阈值最老轮次 LLM 折叠为摘要（非 Near/Mid/Far 窗口） |
| PlanNotebook 持久化+恢复 | Redis save → 模拟崩溃 → load 恢复 → task 状态一致 |
| Orphan Worker 回收 | Planner 启动 Worker → Kill orchestrator → 重启 → RecoveryService 查 AgentScope StateStore 回收 handoff |
| Worker 超时重试 | Worker 120s 不返回 → taskRetry → 重试成功 |
| Worker 重试耗尽 → Planner replan | 两次 Worker 超时 → replan → 新 task 执行成功 |
| Planner LLM 重试 | Gateway 5xx → 退避重试 → 成功 |
| Planner LLM 全失败 → 降级 | 2 次 retry 耗尽 → 综合已收集信息回答 |
| Evaluator 超时 → Planner 自判（Chat） | Evaluator 30s 超时 → Planner.selfAssess() → 下轮恢复 Evaluator |
| Stuck 检测 | 连续 2 轮 goalCompletion 不变 → 强制综合回答 |
| Idempotent replay | 同一 Notebook version 写入两次 → 第二次跳过 |
| Redis 不可用降级 | Redis 挂 → 内存模式运行 → 正常结束 → MySQL 写成功 |

### 12.2 Live

| # | 场景 | scene | 预期 |
|---|------|:---:|------|
| H1 | 分析Q2销售下降，制定改进方案，评估预算 | chat | Planner→Worker(工具调用)→Evaluator→Planner 综合回答 |
| H2 | 修复项目 SQL 注入风险 | task | Planner→Worker(工具调用+内部spawn子Agent)→Planner 自判→综合 |
| H3 | 简单问答（回归） | / | 走 ReactExecutor（planMode=none） |
| H4 | `#workflow` 绑定（回归） | / | 走 PlanWorkflowExecutor |
| H5 | 长任务上下文压缩 | chat | rounds 超 `near-keep-rounds` 后 H1 折叠摘要生效，L1 中旧 handoff 被摘要 |
| H6 | 崩溃恢复 | chat | Kill orchestrator 在第 2 轮 Worker 执行中 → 重启 → 恢复 Notebook → 继续执行正常完成 |
| H7 | Worker 超时 → 重试奏效 | task | Worker 超时触发 taskRetry → 重试后产出正确 handoff → 继续 |
| H8 | 三层上下文隔离（task） | task | 并行 Worker 只读各自 `dependsOn` 前置产出；无依赖 Worker 互不可见；Worker 内部 think/tool 不回流，仅 handoff 双写进 H1（P1）与 L1 尾部（G） |
| H9 | KV 红线：定向结果不进稳定前缀 | task | 三个并行 Worker 的稳定前缀字节一致（Tier 0 + 任务目标 + 共享快照），P2 定向结果只出现在各 Worker 动态段（query 附近） |
| H10 | S 域失败闭环 + 审计摘要 | task | 单 Worker 异常仅本任务重试，错误栈不扩散；异常结构化摘要写入 P1 可审计；G 域无残留 |
| H11 | HIERARCHICAL 分层增量规划 | task | 陌生仓库功能开发：首轮仅输出 4 阶段骨架（代码调研→方案设计→代码实现→回归验证），调研完成后才细拆「代码实现」为文件级 task DAG；阶段骨架字节稳定（Tier 1），细节在 Tier 2 尾部 |
| H12 | 触发式重规划边界 | task | 模拟信息缺口（执行中发现新依赖）：仅 replan 当前阶段剩余任务，阶段骨架不变，已完成 task 不重跑（幂等）；`max-phase-replans=3` 触发后强制综合 |
| H13 | 资源溢出 → 拆分而非停止 | task | 制造接近 `max-duration-ms` 的长任务：Planner 拆分/简化剩余任务继续，而非直接停止 |

---

## 13. 风险与对策

| 风险 | 对策 |
|------|------|
| Planner 每轮多调 LLM | timeout 60s + 小步规划 + 轮次硬顶 5 |
| Evaluator 误判 | 输出 reason 可审计；误判→多跑 1 轮（stale 降级） |
| Task 模式 Planner 自判不准确 | Worker handoff 含详细评估信息，Planner 基于此决策 |
| Worker 上下文不足导致跑偏 | `forWorker()` 注入 taskGoal + upstreamResults（dependsOn 定向）+ constraints + expectedOutput + successCriteria（v10 定稿，**不注入 L2 用户画像**）。Worker 内部 ReAct 可通过工具调用自我采集更多上下文 |
| Planner L1 上下文爆满 | Worker handoff 参与 L1 压缩点前进（同 tool_result，折叠进 Far，**不重排 Near/Mid**） |
| H1 PlanNotebook 膨胀 | 注入块内部两级折叠（v10 定稿 §2.3.4）：近 `near-keep-rounds` 轮原文逐轮追加，超阈值最老轮次 LLM 折叠为摘要；H1 位于注入块（query 前 = Tier 2 尾部语义），变化只 miss 尾部小块 |
| Worker handoff 破坏 Planner prefix | handoff 双写（§2.4）：L1 尾部 append（C2 天然友好）+ H1（Tier 2 尾部）；**禁止**注入 L1 中段 / 压缩时重排消息 |
| upstreamResults 全量注入导致每个 Worker 都 miss | 改由 `plan_shared_memory` 按需读取，稳定前缀跨 worker 复用（§2.4） |
| S 域中间推理透传到 P/G 域（v7） | handoff 双写只传**标准化产出**；Worker 内部 think/tool 留在 S 域 scratchpad，任务结束即销毁（§2.5.2） |
| 并行 Worker 共享越界（v7） | `plan_shared_memory` 按 `dependsOn` 定向传递，无依赖并行 Worker 完全不可见（§2.5.3 规则 1） |
| 底层能力绕过 Executor 鉴权（v7） | 工具/模型/记忆/缓存访问全经 Executor（toolWhitelist + HITL + `call_scene` + Token 预算），不直接暴露给 S 域（§2.5.3 规则 5） |
| Worker 前缀被定向结果污染 → 跨 worker 前缀复用失效（v7） | KV 红线：P2 定向结果只渲染 **S 动态段**，禁止写入 Worker 稳定前缀，否则每个 Worker 前缀字节不同、全量 miss（§2.5.3 规则 6） |
| S 域无界增长（v7） | Worker 内部 ReAct 同样 compaction/eviction（对齐五层压缩点 / 4.7.8），S 域内部裁剪不回流 P/G（§2.5.3 注记） |
| S 域销毁后审计丢失（v7） | 执行态留 S 域（重试用），**结构化异常摘要写 P1**（审计用），G 域干净（§2.5.3 规则 4） |
| 复杂任务首轮强拆导致失败（v8） | HIERARCHICAL 三态：信息不足时首轮只出阶段骨架（phase-closed），到达阶段再细拆（§0.2/§4.1.1） |
| 重规划全盘推翻 / 无限循环（v8） | 边界规则：局部修正（阶段骨架不变）+ 保留成果（幂等）+ `max-phase-replans=3` + 计划相似度校验（§5.2.2） |
| 重规划后已完成文件被回滚（v8） | 同 checkout 内已完成文件不动；隔离/回滚语义交给 Git + 用户显式 checkout（无应用层锁，task-workspace-codex §2.3） |
| 阶段骨架抖动破坏 Tier 1 前缀（v8） | H1 拆分：骨架 Tier 1 幂等 upsert（仅阶段切换失效一次），细节 Tier 2 尾部（§2.3.4 v8 注记） |
| 阶段细拆成本失控（v8） | `call_scene=plan-phase` 轻量模型分层 + 全局 `max-rounds`/`max-duration-ms` 硬顶 |
| 官方 skill 目录 / 个人配置混入前缀 | 分层（§2.4.2）：system skills → Tier 0 目录摘要，user skills → Tier 1 幂等块，命中正文 → Tier 2 |
| Worker 并行时上游结果还未产出 | `dependsOn` 约束串行执行，无依赖的 Worker 并行时才共享同一上游快照 
| 用户在 Planner-Worker 执行中发 follow-up | Planner 接收新消息，根据 H1 状态决定中断/等待/redirect |
| orchestrator 崩溃/重启 | PlanNotebook Redis+MySQL 持久化 + `PlanNotebookRecoveryService` 恢复；Orphan Worker 通过 AgentScope StateStore 回收 handoff |
| Worker 超时/崩溃 | `taskRetryMax`=1 次重试 → 失败则 Planner replan；Worker **内部故障转移由 AgentScope 2.0 StateStore 自动处理**，Planner 只关心超时后的重试/replan |
| Evaluator 超时（Chat） | 降级 Planner 自判本轮 → 下轮恢复 Evaluator；不中断整体 Loop |
| Planner LLM 调用全失败 | 2 次 retry 耗尽 → 有结果则综合回答 / 无结果则降级 ReactExecutor |
| Checkpoint 与执行状态不一致 | `version` 自增 + 幂等重放；版本号跳跃 > 3 → 告警，人工审核 |
| Redis 不可用 | 降级内存模式（本次 Loop 不可恢复）→ Loop 结束时兜底写 MySQL |

---

## 14. 明确不做

- L3 分类器判断 taskDecomposition（Planner 自行决定）
- 独立 Synthesizer（合并到 Planner）
- 独立意图识别判断 full/incremental
- 用户确认（Plan Approval）— **已废弃**（[planner-executor-rebuild D5](../2026-08-05-planner-executor-rebuild-design.md) 删除 PlanApproval；Harness 目标对齐/失败预算替代，需求澄清走 `request_decision` 4.7.9）
- Worker 用 `forSubAgent()=empty()` — Worker 是 Planner 的工具调用，需 `forWorker()` 丰富上下文
- AgentScope 2.0 PlanModeContextState

---

## 15. 与 Cursor 架构对齐

| 我们的设计 | Cursor | 对齐 |
|-----------|--------|:---:|
| Planner 只规划不执行 | "a planner never implements" | ✅ |
| Worker 只执行不规划 | "a worker never plans" | ✅ |
| Worker = Planner 的工具调用（forWorker 丰富上下文） | Worker 是 Planner 调度的独立执行单元 | ✅ |
| Worker 内部可 spawn 隔离子 Agent | Worker 内部可用 tool 细分任务 | ✅ |
| Worker handoff → Planner | Worker handoff → Planner | ✅ |
| Planner 全量上下文 = L1+L2+H1 | Planner 维护任务状态 + 对话历史 | ✅ |
| Chat Evaluator (Maker-Checker) | 无（代码可程序化验证） | ⚠️ 领域差异 |
| Task 模式无 Evaluator | Worker 产出代码即验证 | ✅ |
| Planner 综合回答 | 无（代码即最终产物） | ⚠️ 领域差异 |
| Worker 内部 ReAct 记忆 = 独立 scratchpad | Worker 独立上下文窗口 | ✅ |

**核心共识**：Planner 不做执行，Worker 不做规划。Worker 是 Planner 的工具调用，不是隔离子 Agent——这一层对应 Cursor 的 Worker 角色。Worker 内部的 spawn 才是真正的子 Agent，对应 Cursor Worker 内部的工具调用（如 linter）。

Chat 模式多了 Evaluator 和 Planner 综合回答——因为知识工作的产出无法像代码一样程序化验证，需要 LLM 评估 + 结构化报告。

---

## 16. 实施依赖链

```
v3 路由设计 → planMode（RoutingResult 新增）+ scene（用户选择，前端传入）
  → ResourceDispatcher（新增 PlannerHarnessLoop 分支）
    → PlannerHarnessExecutor
      → HarnessPlanner（全量 L1+L2+H1，复刻 ReAct MAIN 记忆）
        → WorkerContextFactory（forWorker 上下文构造）
          → Worker 工具调用（内部 ReAct + spawn 子 Agent）
            → handoff → Planner L1（视同 tool_result）
              → Chat: Evaluator / Task: Planner 自判
                → Planner 综合回答
```

---

## 17. 关键文件索引

| 文件 | 改动 | 说明 |
|------|------|------|
| `orchestrator/.../plan/harness/PlanNotebook.java` | 新建 | H1 跨轮状态 POJO（scene 驱动，含 `version` 幂等计数器） |
| `orchestrator/.../plan/harness/HarnessPlanner.java` | 新建 | 规划+决策+综合（全量 L1+L2+H1） |
| `orchestrator/.../plan/harness/WorkerContextFactory.java` | 新建 | 构造 forWorker 上下文 |
| `orchestrator/.../plan/harness/GoalEvaluator.java` | 新建 | Chat 全局评估 |
| `orchestrator/.../plan/harness/TaskEvaluator.java` | 新建 | Chat 逐 task 评估 |
| `orchestrator/.../plan/harness/PlannerHarnessLoop.java` | 新建 | 双模编排引擎（Worker 工具调用 + handoff 回流 + Checkpoint C1-C4 + 超时/重试/Stuck 检测） |
| `orchestrator/.../plan/harness/PlanNotebookStore.java` | 新建 | Redis + MySQL 双写接口（save/load/updateTaskStatus/renewTtl/delete） |
| `orchestrator/.../plan/harness/PlanNotebookStoreImpl.java` | 新建 | Store 实现（Checkpoint 版本控制 + 幂等重放 + MySQL 异步写入） |
| `orchestrator/.../plan/harness/PlanNotebookRecoveryService.java` | 新建 | 崩溃恢复入口（load → 检查 IN_PROGRESS → 查 AgentScope StateStore → resumeloop） |
| `orchestrator/.../plan/harness/PlanSharedMemoryStore.java` | 新建 | P2 域载体（§2.5.1）：按 `dependsOn` 定向传递的任务产出存储（Redis），只读注入 S 动态段 + run 结束清理 |
| `orchestrator/.../execution/PlannerHarnessExecutor.java` | 新建 | ResourceDispatcher 分发入口 |
| `orchestrator/.../routing/RoutingResult.java` | 修改 | 新增 planMode 字段（L3 输出），scene 已存在 |
| `orchestrator/.../execution/ResourceDispatcher.java` | 修改 | 新增 planMode=harness → PlannerHarnessExecutor 分支 |
| `orchestrator/.../agent/runtime/AssembledContext.java` | 修改 | 新增 `forWorker()` 工厂方法 |
| `orchestrator/.../context/compression/` | 修改 | H1 Near/Mid/Far 压缩策略 |
| `docker/mysql/init/15-planner-harness.sql` | 新建 | `planner_notebooks` 表 DDL |
| Catalog `planner.harness` | 新建 | Planner system prompt（含 Worker 工具调用描述） |
| Catalog `planner.phase` | 新建 | 阶段细拆 prompt（HIERARCHICAL，输出当前阶段 task DAG） |
| Catalog `harness.worker` | 新建 | Worker system prompt（forWorker 模板） |
| Catalog `harness.task-evaluator` | 新建 | Task 评估 prompt |
| Catalog `harness.goal-evaluator` | 新建 | 全局评估 prompt |
