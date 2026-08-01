# 统一资源路由设计（Pre-Routing + L0-L2 快速路径 + L3 语义兜底）

> **状态**：设计稿（已评审 v3 — 2026-07-30）
> **日期**：2026-07-29（初稿）· 2026-07-30（v2 修订：全部 agent 为子 Agent）· 2026-07-30（v3 修订：深层语义兜底替代 L3 跳过/GUIDED）
> **编号**：阶段四增量（路由层重构：ExecutionMode → ResourceType）
> **前置**：[multi-agent-unified-design](./2026-07-29-multi-agent-unified-design.md)（agent_definition 扩展 + scene 字段）· [workflow-structured-io](./2026-07-24-workflow-structured-io-design.md)（workflow 结构化 I/O 完成后 AgentNodeHandler 契约稳定）
> **一句话**：删除 `ExecutionMode` 路由体系，改为 Pre-Routing + L0-L2 快速路径 + L3 语义兜底（L2 有候选→快速分类 / L2 空→全量 L1 上下文+完整 Catalog 深层召回）。所有命中的 agent 一律为子 Agent。L3 输出 `planMode`（none→ReAct / harness→Planner-Worker Loop），`scene` 来自用户选择作为 L3 输入参数。Pre-Routing 处理 HITL/Plan/续跑等系统等待态复用。

---

## 0. 术语约定

| 术语 | 含义 |
|------|------|
| 资源（Resource） | workflow / agent / skill / react 四种可路由目标 |
| 路由（Routing） | 从用户输入到 `RoutingResult` 的决策过程 |
| 执行（Execution） | 从 `RoutingResult` 到 `Flux<StreamToken>` 的派发过程 |
| Pre-Routing | 在路由链之前拦截系统等待态（HITL/Plan/续跑），复用上次路由结果 — **本 spec 不动** |
| 快速路径（Fast Path） | L0-L2 + L3 快速分类（L2 有候选时） |
| 深层语义兜底（Deep Semantic Fallback） | L2 全空或极低分时，用**全量 L1 会话快照 + 完整 Agent/Skill/Workflow Catalog** 做 LLM 语义召回，解决指代消解 |
| 累积器（Accumulator） | 跨 L0-L2 逐层收集 agentIds / skillIds / workflowId 的状态容器 |
| 主 Agent（Main Agent） | **通用 ReAct** — 唯一的编排者和综合者 |
| 子 Agent（Sub Agent） | 路由命中的 agent — 由主 Agent 通过 `spawn_subagent(agent_id=...)` 按需委派 |
| 终止信号（STOP） | WORKFLOW 在任意层命中时立即返回 RoutingResult，跳过后续所有层 |

---

## 1. 背景与问题

### 1.1 现状：ExecutionMode 路由体系

当前路由层以 `ExecutionMode` 为中心：

```
用户输入 → ExecutionPlanRouter
            ├── ForcedExecutionRouter（executionPreference 用户指定）
            ├── UnifiedRuleRoutingPolicy（L1 规则）
            │   ├── WORKFLOW（L2 规则命中）
            │   └── PEER_COLLAB（L1 句式 §E）
            └── LlmClassifierRoutingPolicy（L3 LLM 分类）
                ├── REACT
                ├── WORKFLOW
                ├── PLAN_WORKFLOW
                └── PEER_COLLAB
```

核心问题：

| 问题 | 说明 |
|------|------|
| **模式 vs 资源混淆** | `WORKFLOW` 既是执行模式又是资源类型，`PEER_COLLAB` 是模式但不是资源（无 resourceId），`REACT` 是兜底但不代表任何资源 |
| **workflow 无语义召回** | 只能靠 `#workflow-id` 显式绑定或 L1 规则命中，用户说「走报销流程」必须记住 workflow ID |
| **agent/skill 无语义召回** | L2 全空，L3 只做模式分类不选具体资源，「指定智能体」全靠 `$agent-id` 绑定 |
| **多 Agent 协作缺失** | `PEER_COLLAB` 是独立 ExecutionMode（待实现），与 ReAct 边界模糊；spawn_subagent 已支持 `agent_id` 参数，但 LLM 不知道有哪些预置 agent 可调用 |
| **首次命中即返回** | `$agent-A` 命中后 L1/L2/L3 全跳过，用户显式绑定和语义召回无法合并；分类器识别的资源被丢弃 |
| **主子 Agent 模型错误** | 原「第一个 `$` 的 agent 做主 Agent」把业务 agent 硬推上编排位——合同审查 agent 的 prompt 是「审查合同条款」，不是「理解用户多步需求、编排子任务」 |
| **ForcedExecutionRouter** | 用户手动选模式，与「资源路由」理念冲突——用户应该选资源（#/$/@），不是选模式 |
| **Plan-Workflow 在路由入口** | 动态 DAG 脆弱，LLM 产出不可靠，且用户确认后仍有退化风险 |

### 1.2 目标

1. 删除 `ExecutionMode` 路由体系，改为 `ResourceType` 驱动
2. **Pre-Routing** 优先：HITL/Plan/续跑等系统等待态复用，不进入路由链
3. L0-L2 快速路径 + L3 语义兜底：L2 有候选→快速分类（200-500ms）；L2 全空→深层语义兜底（用全量 L1 上下文+完整 Catalog 解决指代消解）
4. **所有路由命中的 agent 一律为子 Agent**，通用 ReAct 为唯一主 Agent
5. agent 和 skill 可共存（同入 ReactExecutor），workflow 独占（进 WorkflowExecutor）
6. `Plan-Workflow` 保留代码但去掉路由入口
7. 删除 `ForcedExecutionRouter`、`PEER_COLLAB`、`GUIDED` 兜底
8. `scene` 字段贯穿全链路

---

## 2. 路由层架构

### 2.1 路由链路全貌

```
用户输入 + scene
      │
      ├── Pre-Routing（< 1ms）← HITL/Plan 确认/续跑 等系统等待态复用，不在本 spec 范围
      │   命中 → 复用上次 RoutingResult，不进入路由链
      │
      └── RoutingPolicyChain —— 路由链入口
           │
           ├── L0 显式绑定（< 1ms）— 永远跑
           │   #workflow-id → STOP
           │   $agent-id(s) → agentIds += [...]
           │   @skill-id(s) → skillIds += [...]
           │
           ├── L1 规则匹配（< 5ms）— 永远跑
           │   workflow 规则命中 → STOP
           │   agent/skill 规则 → 补充 agentIds / skillIds
           │
           ├── L2 三路语义召回（30-100ms）— 永远跑
           │   workflow ≥ 0.88 → STOP
           │   agent ≥ 0.85 → agentIds += [...]
           │   skill ≥ 0.85 → skillIds += [...]
           │   所有候选列表存 acc
           │
           └── L3 语义兜底（200-500ms 或 500-800ms）— 永远跑
               │
               ├── ● 候选列表非空 → 【L3 快速分类】
               │   输入：userQuery + L2 候选 Top-9 + scene
               │   输出：agentIds / skillIds / planMode / confidence
               │   耗时：~200-500ms
               │
               └── ● 候选列表为空 → 【深层语义兜底】← 指代消解
                   输入：userQuery + 全量 L1 会话快照 + 全量 Catalog + scene
                   输出：agentIds / skillIds / workflowId / planMode / confidence
                   耗时：~500-800ms

├── planMode=none  → ResourceDispatcher → ReactExecutor
└── planMode=harness → ResourceDispatcher → PlannerHarnessExecutor（Chat/Task 由 scene 区分）
```

> **关键区分**：Pre-Routing 处理「系统正在等用户回答」（HITL 确认 / Plan 审批 / 续跑），语义上是从系统发散到用户；深层语义兜底处理「用户发来指代词 / 无明确信号的追问」（"那个" / "第一个" / "继续"），语义上是从用户收敛到系统。两者隔离，不动 Pre-Routing。

### 2.2 核心数据模型

#### RoutingResult（替代 ExecutionPlan）

```java
package com.sunshine.orchestrator.routing;

import java.util.List;
import java.util.Map;

/** 路由输出 — 统一承载 workflow/agent/skill/react 四种决策结果 */
public record RoutingResult(
    ResourceType type,           // 执行路径标识
    String workflowId,           // type=WORKFLOW 时非空
    List<String> agentIds,       // 全部命中的 agent（L0+L1+L2+L3 合集去重），全部作为子 Agent
    List<String> skillIds,       // 全部命中的 skill（L0+L1+L2+L3 合集去重）
    String scene,                // chat / task（来自用户选择，L3 作为输入参数影响 planMode 判定）
    String planMode,             // "none" | "harness"（仅 L3 输出；none→ReactExecutor，harness→PlannerHarnessExecutor）
    Map<String, String> params,  // effectiveQuery / __l2Candidates 等
    String reason                // 路由来源追踪（l0:agent / l1:rule:xxx / l2:semantic / l3:classify / l3:deep_semantic / fallback:silent）
) {
    public enum ResourceType {
        /** 静态工作流 — 独占 WorkflowExecutor */
        WORKFLOW,
        /** 路由命中了 agent — 前端展示标识；planMode=none→ReactExecutor / planMode=harness→PlannerHarnessExecutor */
        AGENT,
        /** 仅命中 skill — 前端展示标识；planMode=none→ReactExecutor / planMode=harness→PlannerHarnessExecutor */
        SKILL,
        /** 静默兜底 — agentIds/skillIds 空，planMode=none，通用 ReAct */
        REACT
    }

    // ---- 工厂方法 ----

    public static RoutingResult workflow(String workflowId, String scene, String reason) {
        return new RoutingResult(ResourceType.WORKFLOW, workflowId, List.of(), List.of(),
                scene, "none", Map.of(), reason);
    }

    public static RoutingResult silentFallback(String scene, String reason) {
        return new RoutingResult(ResourceType.REACT, null, List.of(), List.of(),
                scene, "none", Map.of(), reason);
    }

    // ---- 便捷判断 ----

    public boolean isWorkflow() { return type == ResourceType.WORKFLOW; }
    public boolean isHarness() { return "harness".equals(planMode); }

    public boolean usesReactExecutor() {
        return (type == ResourceType.AGENT || type == ResourceType.SKILL || type == ResourceType.REACT)
                && "none".equals(planMode);
    }

    public boolean usesPlannerHarnessExecutor() {
        return (type == ResourceType.AGENT || type == ResourceType.SKILL)
                && "harness".equals(planMode);
    }

    public boolean hasAgents() { return agentIds != null && !agentIds.isEmpty(); }
    public boolean hasSkills() { return skillIds != null && !skillIds.isEmpty(); }
}
```

> **v3 变更**：移除 `GUIDED` 类型、`confidence` 字段、`candidates` 字段、`guidedFallback` 工厂方法、`is_no_match` 标志位。新增 `planMode` 字段（none→ReactExecutor / harness→PlannerHarnessExecutor）。兜底统一为静默 REACT（不再打断用户做候选选择）。`scene` 来自用户选择，L3 以此为输入调整 `planMode` 判定规则。

#### RoutingContext（替代现有 RoutingContext）

```java
package com.sunshine.orchestrator.routing.policy;

import com.sunshine.orchestrator.context.AssembledContext;
import java.util.HashMap;
import java.util.Map;

/** 单次路由请求上下文 — scene 贯穿全链路 */
public record RoutingContext(
    String userMessage,
    String traceMessageId,
    String scene,           // "chat" | "task"
    AssembledContext memory
) {
    private static final Map<String, Object> attributes = new HashMap<>();

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) { return (T) attributes.get(key); }

    public void setAttribute(String key, Object value) { attributes.put(key, value); }

    public boolean isChatScene() { return "chat".equals(scene); }
    public boolean isTaskScene() { return "task".equals(scene); }
}
```

> **v4 注记（scene 命名隔离）**：本 spec 的 `scene` = **用户选择场景**（chat/task，贯穿路由与上下文组装）。llm-gateway 侧另有 **`call_scene`** = **LLM 调用点**（plan/worker/evaluator/rewrite 等，用于 5.3 模型路由，见 [phase5 §5.3](./phase5-operation-openness-design.md)）。**两个字段语义不同、禁止合并**——harness 场景下 orchestrator 需同时传「用户 scene=task」与「调用点 call_scene=plan/worker」，同名字段会冲突。协议上：`scene` 进路由/上下文，`call_scene` 只进 `ChatCompletionRequest` 扩展字段，BFF/Gateway 均只透传不自填。

#### RoutingOutcome（策略层返回信号）

```java
package com.sunshine.orchestrator.routing.policy;

/** 路由层返回信号 */
public enum RoutingOutcome {
    /** 继续下一层 */
    CONTINUE,
    /** WORKFLOW 独占命中 — 立即终止路由链，构建 RoutingResult 返回 */
    STOP
}
```

### 2.3 RoutingPolicyChain（累积收集 + WORKFLOW 终止 + L3 必跑）

路由链核心编排：L0/L1/L2 逐层累积，WORKFLOW 立即终止，L3 **始终运行**（候选非空→快速分类 / 候选空→深层语义兜底）。

```java
package com.sunshine.orchestrator.routing.policy;

import com.sunshine.orchestrator.routing.RoutingResult;
import com.sunshine.orchestrator.routing.RoutingResult.ResourceType;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.*;

@Component
@RequiredArgsConstructor
public class RoutingPolicyChain {
    private final List<RoutingPolicy> policies;
    private List<RoutingPolicy> sorted;

    @PostConstruct
    void init() {
        sorted = policies.stream()
                .sorted(Comparator.comparingInt(RoutingPolicy::order))
                .toList();
    }

    public Mono<RoutingResult> route(RoutingContext ctx) {
        RoutingAccumulator acc = new RoutingAccumulator(ctx.scene());

        return runL0(ctx, acc)       // 永远跑 (< 1ms)
                .flatMap(keepGoing -> {
                    if (!keepGoing) return Mono.just(acc.buildWorkflowStop());
                    return runL1(ctx, acc);  // 永远跑 (< 5ms)
                })
                .flatMap(keepGoing -> {
                    if (!keepGoing) return Mono.just(acc.buildWorkflowStop());
                    return runL2(ctx, acc);  // 永远跑 (30-100ms)
                })
                .flatMap(keepGoing -> {
                    if (!keepGoing) return Mono.just(acc.buildWorkflowStop());

                    // L3 始终运行 — 候选非空→快速分类，候选空→深层语义兜底
                    boolean hasCandidates = acc.hasL2Candidates();
                    return runL3(ctx, acc, hasCandidates)
                            .map(decision -> acc.absorbL3(decision));
                });
    }

    // ---- 各层运行器 ----

    private Mono<Boolean> runL0(RoutingContext ctx, RoutingAccumulator acc) {
        RoutingPolicy l0 = sorted.get(0);
        return l0.tryRoute(ctx).map(outcome -> {
            if (outcome instanceof RoutingPolicy.L0Result r) {
                return acc.absorbL0(r);
            }
            return true;
        });
    }

    private Mono<Boolean> runL1(RoutingContext ctx, RoutingAccumulator acc) {
        RoutingPolicy l1 = findPolicy(10);
        if (l1 == null) return Mono.just(true);
        return l1.tryRoute(ctx).map(match -> {
            if (match == null) return true;
            return acc.absorbL1((RoutingResult) match);
        });
    }

    private Mono<Boolean> runL2(RoutingContext ctx, RoutingAccumulator acc) {
        RoutingPolicy l2 = findPolicy(20);
        if (l2 == null) return Mono.just(true);
        return l2.tryRoute(ctx).map(candidates ->
                acc.absorbL2((List<ScoredResource>) candidates));
    }

    private Mono<RoutingResult.L3Decision> runL3(RoutingContext ctx,
                                                   RoutingAccumulator acc,
                                                   boolean hasCandidates) {
        RoutingPolicy l3 = findPolicy(30);
        if (l3 == null) return Mono.just(RoutingResult.L3Decision.empty());

        if (hasCandidates) {
            // 快速路径：传给 L2 候选清单
            return ((LlmClassifierRoutingPolicy) l3)
                    .tryRouteWithCandidates(ctx, acc.getL2Candidates());
        } else {
            // 深层语义兜底：全量 L1 上下文 + 完整 Catalog
            return ((LlmClassifierRoutingPolicy) l3)
                    .tryRouteDeepSemantic(ctx);
        }
    }

    private RoutingPolicy findPolicy(int order) {
        return sorted.stream().filter(p -> p.order() == order).findFirst().orElse(null);
    }
}
```

---

### 2.4 RoutingAccumulator（跨层累积 → L3 兜底）

L0/L1/L2 逐层收集 agentIds/skillIds/L2 候选。只有 WORKFLOW 终止。agent/skill 结果累积到底，到 L3 做最终决策（快速分类 或 深层语义兜底）。

```java
package com.sunshine.orchestrator.routing.policy;

import com.sunshine.orchestrator.routing.RoutingResult;
import com.sunshine.orchestrator.routing.RoutingResult.ResourceType;
import java.util.*;

/**
 * 跨路由层累积状态 — WORKFLOW 独占终止，agent/skill 逐层收集后交 L3 最终决策。
 */
class RoutingAccumulator {
    private String scene;
    private String workflowId;
    private String workflowReason;
    private final Set<String> agentIds = new LinkedHashSet<>();
    private final Set<String> skillIds = new LinkedHashSet<>();
    private String effectiveQuery;
    private String l0Reason;
    private String l3PlanMode = "none";  // L3 输出的 planMode，默认 none
    private final List<ScoredResource> l2Candidates = new ArrayList<>();

    RoutingAccumulator(String scene) { this.scene = scene; }

    // ---- absorb 方法 ----

    boolean absorbL0(L0Result result) {
        if (result.isWorkflow()) {
            workflowId = result.workflowId();
            workflowReason = "l0:workflow";
            return false; // STOP
        }
        if (!result.agentIds().isEmpty()) {
            agentIds.addAll(result.agentIds());
            effectiveQuery = result.effectiveQuery();
            l0Reason = result.agentIds().size() > 1
                    ? "l0:agent:multi:" + result.agentIds().size()
                    : "l0:agent";
        }
        if (!result.skillIds().isEmpty()) {
            skillIds.addAll(result.skillIds());
            if (l0Reason == null) l0Reason = "l0:skill";
        }
        return true;
    }

    boolean absorbL1(RoutingResult ruleMatch) {
        if (ruleMatch == null) return true;
        if (ruleMatch.isWorkflow()) {
            workflowId = ruleMatch.workflowId();
            workflowReason = ruleMatch.reason();
            return false;
        }
        if (ruleMatch.hasAgents()) agentIds.addAll(ruleMatch.agentIds());
        if (ruleMatch.hasSkills()) skillIds.addAll(ruleMatch.skillIds());
        return true;
    }

    boolean absorbL2(List<ScoredResource> merged) {
        if (merged == null || merged.isEmpty()) return true;

        Optional<ScoredResource> topWf = merged.stream()
                .filter(r -> r.type() == ResourceType.WORKFLOW && r.score() >= 0.88)
                .findFirst();
        if (topWf.isPresent()) {
            workflowId = topWf.get().id();
            workflowReason = "l2:workflow:" + topWf.get().score();
            return false; // STOP
        }

        merged.stream()
                .filter(r -> r.type() == ResourceType.AGENT && r.score() >= 0.85)
                .forEach(r -> agentIds.add(r.id()));

        merged.stream()
                .filter(r -> r.type() == ResourceType.SKILL && r.score() >= 0.85)
                .forEach(r -> skillIds.add(r.id()));

        l2Candidates.addAll(merged);
        return true;
    }

    /** L3 最终决策（快速分类 或 深层语义兜底） */
    RoutingResult absorbL3(L3Decision decision) {
        if (decision != null && decision.confidence() >= 0.5) {
            if (decision.hasAgents()) agentIds.addAll(decision.agentIds());
            if (decision.hasSkills()) skillIds.addAll(decision.skillIds());
            l3PlanMode = decision.planMode();
        }
        return build();
    }

    // ---- 查询方法 ----

    boolean hasL2Candidates() {
        return !l2Candidates.isEmpty();
    }

    List<ScoredResource> getL2Candidates() {
        return List.copyOf(l2Candidates);
    }

    // ---- build ----

    RoutingResult buildWorkflowStop() {
        return RoutingResult.workflow(workflowId, scene, workflowReason);
    }

    private RoutingResult build() {
        if (agentIds.isEmpty() && skillIds.isEmpty()) {
            return RoutingResult.silentFallback(scene, "fallback:silent");
        }

        ResourceType type = !agentIds.isEmpty() ? ResourceType.AGENT : ResourceType.SKILL;
        String reason = l0Reason != null ? l0Reason : "l2+3";
        return new RoutingResult(type, null,
                List.copyOf(agentIds), List.copyOf(skillIds),
                scene, l3PlanMode,
                effectiveQuery != null ? Map.of("effectiveQuery", effectiveQuery) : Map.of(),
                reason);
    }
}
```

> **v3 简化**：删除 `canSkipL3()`、`buildWithoutL3()`、`guidedFallback`、`confidence`、`is_no_match`。新增 `l3PlanMode`。L3 始终跑，候选非空→快速分类，候选空→深层语义兜底。`planMode` 默认 `"none"`，L3 可覆盖为 `"harness"`。

### 2.5 典型请求耗时估算

| 场景 | L0 | L1 | L2 | L3 | 总耗时 | L3 模式 |
|------|----|----|----|----|--------|---------|
| `#expense-flow 报销` | 1ms | — | — | — | **< 1ms** | L0 WORKFLOW→STOP |
| `$compliance-checker 审查合同` | 1ms | 5ms | 50ms | 250ms | **~300ms** | 快速分类（L2 有候选） |
| 闲聊「今天天气不错」 | 1ms | 5ms | 50ms | 550ms | **~600ms** | 深层语义兜底（L2 空→全量上下文） |
| 「怎么处理报销单」 | 1ms | 5ms | 50ms | 300ms | **~350ms** | 快速分类（L2 有 workflow/agent 候选） |
| 「那个/第一个/继续」指代词 | 1ms | 5ms | 50ms | 600ms | **~650ms** | 深层语义兜底（指代词 L2 不可能匹配） |
| 「合规审查+财务分析」多意图 | 1ms | 5ms | 50ms | 400ms | **~450ms** | 快速分类（多 agent 候选→L3 合并） |

---

## 3. L0：显式绑定路由

用户输入中的 `#workflow-id` / `$agent-id` / `@skill-id` 为最高优先级。

### 3.1 核心语义：全部 Agent 均为子 Agent

**关键设计变更（v2）**：`$agent` 绑定不再指定「主 Agent」。所有路由命中的 agent 一律作为**子 Agent**，由**通用 ReAct** 作为唯一主 Agent（编排者 + 综合者）。

> **理由**：业务 agent 的 system prompt 是为特定领域任务设计的（如「审查合同条款」），不是为「理解用户多步需求、分解子任务、编排 spawn、综合结论」设计的。强制执行主 Agent 角色的 agent 会出现编排失误、spawn 遗漏、总结不完整等问题。通用 ReAct 的 system prompt（`mode-overlay.react`）天然包含全套编排规则（taskboard/spawn/并行/串行/HITL），是最合适的主 Agent。

```java
@Component
@RequiredArgsConstructor
public class ExplicitBindingRoutingPolicy implements RoutingPolicy {
    private final WorkflowBindingParser workflowBindingParser;
    private final AgentBindingParser agentBindingParser;
    private final SkillBindingParser skillBindingParser;

    @Override public int order() { return 0; }

    @Override
    public Mono<Object> tryRoute(RoutingContext ctx) {
        String msg = ctx.userMessage();

        // #workflow-id 最高优先级 → STOP（WORKFLOW 独占）
        if (ctx.isChatScene()) {
            var wf = workflowBindingParser.resolve(msg);
            if (wf.bound()) {
                return Mono.just(new L0Result(
                        true, wf.workflowId(), List.of(), List.of(), msg));
            }
        }

        // $agent-id(s) → 全部收集为子 Agent，不设主 Agent
        var agent = agentBindingParser.resolve(msg);
        if (agent.bound()) {
            return Mono.just(new L0Result(
                    false, null,
                    agent.agentIds(),            // 全部 agentId
                    List.of(),
                    agent.effectiveQuery()));     // 剥离 $ 标记后的原文
        }

        // @skill-id(s) → 收集
        var skill = skillBindingParser.resolve(msg);
        if (skill.bound()) {
            return Mono.just(new L0Result(
                    false, null, List.of(),
                    List.of(skill.skillId()), msg));
        }

        return Mono.just(new L0Result(false, null, List.of(), List.of(), msg));
    }

    /** L0 显式绑定结果 */
    public record L0Result(
            boolean isWorkflow,
            String workflowId,
            List<String> agentIds,
            List<String> skillIds,
            String effectiveQuery) {}
}
```

### 3.2 多 $ 绑定语义（v2 修订）

| 输入 | agentIds | 执行语义 |
|------|----------|---------|
| `$agent-A 帮我报销` | [`agent-A`] | 通用 ReAct 做主，agent-A 作为可 spawn 的子 Agent |
| `$agent-A $agent-B 竞品分析` | [`agent-A`, `agent-B`] | 通用 ReAct 做主，两个全平权子 Agent 可按需 spawn |
| `$agent-A $agent-B $agent-C ...` | [`agent-A`, `agent-B`, `agent-C`] | 全平权，主 Agent 自主并行/串行 spawn |

### 3.3 约束

| 规则 | 说明 |
|------|------|
| `#workflow-id` 优先级最高 | `#` 出现时立即返回 WORKFLOW，`$`/`@` 不生效 |
| `#workflow-id` 仅在 `chat` 场景 | task 场景不走 workflow 路由 |
| `$` 和 `@` 可共存 | 两个都收集（agentIds + skillIds），同进 ReactExecutor |
| **$agent 不作为主 Agent** | 全部 agentId 进 agentIds 列表，由通用 ReAct 按需 spawn |
| 未识别的 `$` → 报错 | `$unknown-agent` → 返回 agent not found 错误 |

---

## 4. L1：规则匹配路由

保持现有 `UnifiedRuleRoutingPolicy` 核心逻辑不变，改动点：

1. **输出改为 `RoutingResult`**（不再返回 `ExecutionPlan`）
2. **规则增加 `resourceType` 字段**
3. **WORKFLOW 规则命中 → 返回 STOP 信号**

```java
@Component
@RequiredArgsConstructor
public class RuleBasedRoutingPolicy implements RoutingPolicy {
    private final UnifiedRuleEngine ruleEngine;

    @Override public int order() { return 10; }

    @Override
    public Mono<RoutingResult> tryRoute(RoutingContext ctx) {
        return ruleEngine.match(ctx.userMessage(), ctx.scene())
                .filter(match -> match.confidence() >= 0.85)
                .map(match -> new RoutingResult(
                        match.resourceType(),
                        match.resourceId(),
                        match.resourceType() == ResourceType.AGENT
                                ? List.of(match.resourceId()) : List.of(),
                        match.resourceType() == ResourceType.SKILL
                                ? List.of(match.resourceId()) : List.of(),
                        ctx.scene(),
                        Map.of(),
                        List.of(),
                        "l1:rule:" + match.ruleId(),
                        match.confidence()))
                .defaultIfEmpty(null);
    }
}
```

---

## 5. L2：三路语义召回

### 5.1 三路并行检索（收集模式，非互斥）

```java
@Component
@RequiredArgsConstructor
public class SemanticRoutingPolicy implements RoutingPolicy {
    private final AgentEmbeddingIndex agentIndex;
    private final SkillEmbeddingIndex skillIndex;
    private final WorkflowEmbeddingIndex workflowIndex;

    @Override public int order() { return 20; }

    @Override
    public Mono<List<ScoredResource>> tryRoute(RoutingContext ctx) {
        String query = ctx.userMessage();
        String scene = ctx.scene();

        return Mono.zip(
                agentIndex.search(query, 3, scene)
                        .map(list -> toScored(list, ResourceType.AGENT)),
                skillIndex.search(query, 3, scene)
                        .map(list -> toScored(list, ResourceType.SKILL)),
                workflowIndex.search(query, 3, scene)
                        .map(list -> toScored(list, ResourceType.WORKFLOW))
        ).map(tuple -> {
            List<ScoredResource> merged = new ArrayList<>();
            merged.addAll(tuple.getT1());
            merged.addAll(tuple.getT2());
            merged.addAll(tuple.getT3());
            merged.sort((a, b) -> Double.compare(b.score(), a.score()));
            return merged;
        });
    }

    private List<ScoredResource> toScored(List<Result> list, ResourceType type) {
        return list.stream()
                .map(e -> new ScoredResource(type, e.id(), e.score()))
                .toList();
    }

    /**
     * 分类型直接命中阈值。
     * workflow 误路由成本高（确定性 DAG 整跑一轮），要求更高置信。
     */
    static double directHitThreshold(ResourceType type) {
        return switch (type) {
            case WORKFLOW -> 0.88;
            case AGENT, SKILL -> 0.85;
            default -> 0.85;
        };
    }
}
```

> **注意**：L2 不再做「top-1 直接命中返回」逻辑。所有候选列表交给 `RoutingAccumulator.absorbL2()` 处理——workflow 达阈值 → STOP；agent/skill 达阈值 → 累加到 agentIds/skillIds；全未达阈值 → 候选进 L3。

### 5.2 workflow embedding 索引

**数据源**：workflow-manager DB `workflow_definition` 表（name + description + 触发示例）。

**索引生命周期**：
- 发布/更新 → 重建该 workflow 的 embedding 向量
- 禁用/删除 → 从索引中移除
- 服务启动 → 全量重建

### 5.3 降级路径

embedding 服务不可用时，L2 返回空列表，不阻塞路由链。

---

## 6. L3：LLM 语义兜底（两种模式，始终运行）

L3 不再是一个可选的「分类器步骤」，而是**锁定**的最后语义决策层。根据 L2 的结果分两条路径：

### 6.1 模式 A：快速分类（L2 候选非空）

```
输入：userQuery + L2 候选 Top-9（含 type/id/name/desc/score）
输出：{type, agentIds[], skillIds[], planMode, confidence, reason}
耗时：~200-500ms
```

候选足够 → LLM 从中筛选/合并，不加载全量 Catalog。

**分类器输出格式**：

```json
{
  "type": "AGENT",
  "agentIds": ["compliance-checker", "finance-analyst"],
  "skillIds": ["report-generator"],
  "planMode": "none",
  "confidence": 0.85,
  "reason": "需合规审查和财务分析，线性依赖可一次完成"
}
```

**未命中时**（agentIds 和 skillIds 均为空 → planMode=none → 静默 REACT）：
```json
{"type": "REACT", "agentIds": [], "skillIds": [], "planMode": "none", "confidence": 0, "reason": "no_match"}
```

### 6.2 模式 B：深层语义兜底（L2 候选为空 — 指代消解）

```
触发条件：L2 候选全空 或 全部 score < 0.7
输入：
  1. userQuery（用户的原始输入，如 "那个"、"第一个"、"继续"）
  2. scene（chat / task，来自用户选择，影响 planMode 判定规则）
  3. 全量 L1 会话快照（不限于 4 轮）— 完整的对话上下文
  4. 完整 Agent Catalog（全量，不过滤）：
     每个 agent 包含：id / name / description / 擅长领域 / 触发示例
  5. 完整 Skill Catalog（全量，不过滤）：
     每个 skill 包含：id / name / description / 触发示例
  6. 完整 Workflow Catalog（全量，不过滤）：
     每个 workflow 包含：id / name / description / 触发示例
输出：{type, agentIds[], skillIds[], workflowId?, planMode, confidence, reason}
耗时：~500-800ms
```

**设计意图**：指代词（"那个"、"第一个"、"继续"、"Tell me more"）在 L0-L2 不可能命中任何 agent/skill/workflow。深层语义兜底用**完整上下文 + 完整资源**让 LLM 来推断用户意图。LLM 能看到：
- 上一轮对话说了什么（全量 L1 快照）
- 当前场景（chat/task，影响 planMode 判定规则）
- 全量可用 agent / skill / workflow 清单

从而判断「那个」指的是上轮提到的 workflow，还是 agent，还是只是一般性追问。

**prompt 结构**：

```
你是路由判断助手。用户正向多轮对话发出后续请求，请你根据对话上下文和可用资源列表，判断用户意图。

## 对话历史（全量）
{全量 L1 会话快照：每轮 user/assistant 完整内容}

## 可用智能体（全量，共 N 个）
| agent_id | 名称 | 擅长领域 | 描述 |
|----------|------|---------|------|
| compliance-checker | 合规审查智能体 | 制度检查、法规比对 | 合同条款合规性审查... |
| finance-analyst | 财务分析智能体 | 预算审查、费用分析 | 合同财务条款分析... |
...（全量，不分页）

## 可用技能（全量，共 M 个）
| skill_id | 名称 | 描述 |
|----------|------|------|
| report-generator | 报告生成 | 生成格式化的分析报告... |
...（全量，不分页）

## 可用工作流（全量，共 K 个）
| workflow_id | 名称 | 描述 | 触发示例 |
|-------------|------|------|---------|
| expense-flow | 报销审批流程 | 处理报销申请... | "报销"、"差旅费" |
...（全量，不分页）

## 当前场景
{scene}  // chat: 语义分析类任务，task: 编码/文件产出类任务

## 当前用户输入
{userQuery}

## 输出格式
{type: "AGENT"|"SKILL"|"WORKFLOW"|"REACT", agentIds: string[], skillIds: string[],
 workflowId: string|null, planMode: "none"|"harness", confidence: number 0-1, reason: string}

规则：
- 如果用户是追问上一轮的话题（如 "那个"、"第一个"、"继续"），结合对话历史确定 target
- 如果用户的内容是全新话题，target 选意图最匹配的
- agentIds/skillIds 为空时 → type="REACT", planMode="none"（静默兜底，通用 ReAct）
- planMode 判定：
  - 认知步骤 ≥4、多级依赖、需验证闭环、需探索 → planMode="harness"（Planner-Worker Loop）
  - 线性任务、路径明确 → planMode="none"（通用 ReAct）
  - scene=chat 时偏向语义复杂度（分析深度），scene=task 时偏向工程复杂度（文件/模块数）
- 没把握判定 harness → 走 none（安全网）
- confidence 反映你对判断的确信度
```

### 6.3 决策规则

| 分段 | confidence | 行为 |
|------|-----------|------|
| agentIds/skillIds 空 | N/A | planMode=none → 静默 REACT（通用 ReAct，按 scene 加载对应工具集） |
| 极低置信 | c < 0.5 | 安全网 → agentIds/skillIds 留空，planMode=none，走 REACT |
| 低置信 | 0.5 ≤ c < 0.8 | 采纳 LLM 的 agentIds/skillIds；planMode 仅 c ≥ 0.7 时采纳，否则退为 none |
| 高置信 | c ≥ 0.8 | 直接采纳（含 planMode） |

**planMode 判定依据**（L3 输入参数 `scene` 参与规则调整）：

| scene | planMode=none（通用 ReAct） | planMode=harness（Planner-Worker） |
|-------|---------------------------|----------------------------------|
| chat | 认知步骤 ≤3，线性依赖，路径明确 | 认知步骤 ≥4，多级依赖，需验证闭环，需探索后再定方向 |
| task | 单文件修改，明确重构点 | 多文件修改，跨模块重构，含测试编写，不确定性高 |

**注意**：`scene` 来自用户选择（前端传入），L3 **不作为输出**，仅作为 `planMode` 判定的输入参数。

### 6.4 LlmClassifierRoutingPolicy 实现

```java
@Component
@RequiredArgsConstructor
public class LlmClassifierRoutingPolicy implements RoutingPolicy {
    private final IntentRouter intentRouter;

    @Override public int order() { return 30; }

    /** 模式 A：快速分类（L2 候选非空） */
    Mono<L3Decision> tryRouteWithCandidates(RoutingContext ctx, List<ScoredResource> candidates) {
        return intentRouter.classifyWithCandidates(
                ctx.userMessage(), candidates, ctx.scene())
                .map(L3Decision::from);
    }

    /** 模式 B：深层语义兜底（L2 全空 → 全量上下文 + 全量 Catalog） */
    Mono<L3Decision> tryRouteDeepSemantic(RoutingContext ctx) {
        return intentRouter.classifyDeepSemantic(
                ctx.userMessage(),
                ctx.memory(),                    // 全量 L1 会话快照
                loadFullAgentCatalog(ctx),        // 全量 Agent Catalog
                loadFullSkillCatalog(ctx),        // 全量 Skill Catalog
                loadFullWorkflowCatalog(ctx),     // 全量 Workflow Catalog
                ctx.scene());
    }
}
```

---

## 7. 资源派发器（ResourceDispatcher）

```java
package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.routing.RoutingResult;
import com.sunshine.orchestrator.routing.RoutingResult.ResourceType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
@RequiredArgsConstructor
public class ResourceDispatcher {
    private final WorkflowExecutor workflowExecutor;
    private final ReactExecutor reactExecutor;
    private final PlannerHarnessExecutor plannerHarnessExecutor;  // 新增：planMode=harness
    private final PlanWorkflowExecutor planWorkflowExecutor;      // 保留，仅直接 API

    public Flux<StreamToken> execute(ExecutionStreamContext ctx) {
        RoutingResult r = ctx.routingResult();

        if (r != null && "plan-workflow".equals(r.params().get("__mode"))) {
            return planWorkflowExecutor.execute(ctx);
        }

        ResourceType type = r != null ? r.type() : ResourceType.REACT;
        return switch (type) {
            case WORKFLOW -> workflowExecutor.execute(ctx);
            case AGENT, SKILL, REACT -> {
                if (r != null && r.isHarness()) {
                    yield plannerHarnessExecutor.execute(ctx);
                }
                yield reactExecutor.execute(ctx);
            }
        };
    }
}
```

**关键设计**：
- `AGENT` / `SKILL` / `REACT` 三类根据 `planMode` 分流：
  - `planMode=none` → `ReactExecutor`（通用 ReAct）
  - `planMode=harness` → `PlannerHarnessExecutor`（Planner-Worker Loop，scene 区分 Chat/Task 模式）
- `AGENT` 时 `ReactExecutor` **不加载** agent 的 system prompt 做主 Agent——改为注入 Agent Catalog 摘要到通用 ReAct system prompt
- `SKILL` 时 `ReactExecutor` 注入 skill overlays + 挂载物料
- `WORKFLOW` 独占 `WorkflowExecutor`

---

## 7.1 ReactExecutor 改造（AGENT / SKILL / REACT 统一入口）

```java
// ReactExecutor.execute() 内部：

// 1. 加载通用 ReAct system prompt（base + mode-overlay.react）
String systemPrompt = assembleBaseReactSystem(routingResult.scene());

// 2. 有 agent → 注入 Agent Catalog（不加载 agent 的 system prompt 做主 Agent）
if (routingResult.hasAgents()) {
    systemPrompt += renderAgentCatalog(routingResult.agentIds());
    systemPrompt += renderOrchestrationGuide();
}

// 3. 有 skill → 注入 skill overlays + 挂载物料
if (routingResult.hasSkills()) {
    for (String skillId : routingResult.skillIds()) {
        String overlay = skillCatalogService.getOverlay(skillId);
        if (overlay != null) systemPrompt += "\n" + overlay;
        sandboxSessionLifecycle.mountSkill(sessionId, skillId);
    }
}

// 4. 构造 AgentRunRequest（role=MAIN，system=通用ReAct叠加后的prompt）
AgentRunRequest request = AgentRunRequest.main(assembledCtx, query, ...);
return agentRuntime.run(request);
```

**Agent Catalog 注入格式**：

```
## 可调用的专业智能体
你可以通过 spawn_subagent(agent_id="...", prompt="...", label="...")
将子任务委派给以下智能体：

| agent_id | 名称 | 擅长领域 |
|----------|------|----------|
| compliance-checker | 合规审查智能体 | 制度合规性检查、法规比对、风险识别 |
| finance-analyst | 财务分析智能体 | 预算审查、费用分析、合同财务条款 |

多个独立子任务可在同一轮并行 spawn；有依赖关系的串行。
```

---

## 8. 资源优先级规则一览

### 8.1 WORKFLOW 独占（最高优先级）

| 触发 | 行为 |
|------|------|
| L0 `#workflow-id` | **STOP** → `RoutingResult(type=WORKFLOW)` |
| L1 workflow 规则命中 | **STOP** |
| L2 workflow ≥ 0.88 | **STOP** |
| L3 深层语义兜底返回 workflowId | **STOP** → `RoutingResult(type=WORKFLOW)` |

workflow 出现即**立即终止路由链**，agentIds / skillIds 全部丢弃。

### 8.2 AGENT / SKILL 共存（同入 ReactExecutor）

| agentIds | skillIds | 结果 type | 注入内容 |
|----------|----------|-----------|---------|
| 非空 | 空 | `AGENT` | Agent Catalog |
| 非空 | 非空 | `AGENT` | Agent Catalog + skill overlays |
| 空 | 非空 | `SKILL` | skill overlays |
| 空 | 空 | `REACT` | 通用 ReAct（无注入） |

type 字段主要用于**前端展示标识**，对执行路径无影响——全部进 `ReactExecutor`。

### 8.3 兜底路径

| 条件 | 结果 | 说明 |
|------|------|------|
| L0-L3 全程无 agent/skill/workflow 命中 | `REACT` 静默兜底 | 通用 ReAct 自由执行 |
| Pre-Routing 命中（HITL/Plan/续跑） | 复用上次 RoutingResult | 不进入路由链 |

---

## 9. 兜底策略（已移除 GUIDED）

v3 移除了 `GUIDED` 引导兜底。理由：
1. Pre-Routing 已处理系统等待态（HITL/Plan/续跑），不需要另一层「选候选」打断
2. 深层语义兜底用全量上下文+完整 Catalog 推断指代，覆盖率高
3. 打断用户做「选 A 还是 B」本质上是 L2 embedding 召回不够好的补偿——应优化索引和 prompt 而非甩锅给用户
4. `scene` 来自用户选择，静默 REACT 兜底可获场景适配的工具集（chat→知识分析工具 / task→编码工具），底气更足

所有非 WORKFLOW 且 agentIds/skillIds 为空的路由结果统一为 `REACT` 静默兜底（`planMode=none`）。

---

## 10. 工具加载策略

工具注入分为**三层**，各自独立的加载时机和来源。**主 Agent（通用 ReAct）与子 Agent（spawn）的工具完全分离**。

### 10.1 三层工具架构

```
主 Agent 工具（ReactExecutor 加载）
  ├── L1: 场景默认工具集      ← scene=chat→chat-default / scene=task→task-default
  │    + 硬编码内置工具         ← RAG + sandbox__* x6 + spawn_subagent
  │
  ├── L2: skill 语义召回工具   ← 仅当 skillIds 非空时，按 skill embeddings 召回 Top-K 工具
  │    + skill overlays        ← 各 skill 的 overlay prompt 拼入 system prompt
  │    + skill 物料挂载        ← /skills/{id}/ 挂载进沙箱
  │
  └── L3: Agent Catalog 注入   ← 仅当 agentIds 非空时，注入系统提示（不注入工具给主 Agent）

子 Agent 工具（SpawnSubagentTool 加载，按 agent_id 逐个创建时）
  └── agent_definition.tools_json  ← 该 agent 预配置的工具白名单
```

**关键原则**：主 Agent 的工具 = 通用能力（检索、沙箱、spawn）+ skill 召回的领域工具。子 Agent 的工具 = 该 agent 自己的专属配置。**不交叉**——主 Agent 不加子 Agent 的工具，子 Agent 不加 skill 召回的工具。

### 10.2 各路由类型的加载详情

| 路由结果 | 主 Agent 工具 | 子 Agent 工具 |
|---------|-------------|-------------|
| `REACT`（兜底） | 场景默认工具集 + embedding 召回 Top-K | 无 |
| `SKILL`（仅 skill） | 场景默认工具集 + skill 语义召回 Top-K + skill overlays + 物料 | 无 |
| `AGENT`（仅 agent） | 场景默认工具集（spawn_subagent 告知有哪些 agent 可调） | 每个被 spawn 的子 Agent 加载 agent_definition.tools_json |
| `AGENT` + `SKILL`（共存） | 场景默认工具集 + skill 语义召回 Top-K + skill overlays + 物料 + spawn_subagent（Agent Catalog 注入） | 同上 |
| `WORKFLOW` | 无（不进 ReactExecutor） | workflow 节点定义的工具（PlanJson.params.tool） |

### 10.3 L1：场景默认工具集（所有 ReactExecutor 路径共用）

```
resolveSceneTools(scene, tenantId)
  → ToolManagerClient.fetchSceneDefault(scene, tenantId)
  → tool_set WHERE set_type='tenant_{chat|task}_default' AND tenant_id='{id}'
  → 与 ToolCatalogService.enabledIds(tenantId) 求交

+ 硬编码内置工具（始终注入，不受 tool_set 控制）：
  - search_knowledge        （RAG 检索，kbId 由会话上下文动态注入）
  - sandbox__read / write / edit / glob / grep / exec
  - spawn_subagent          （仅 MAIN，agent.execution.react.subagent.enabled=true）
```

**租户隔离**：每个租户的 `chat-default` 和 `task-default` 完全独立，首次访问自动创建空集。

### 10.4 L2：Skill 语义召回工具

当 `RoutingResult.skillIds` 非空时，ReactExecutor 为每个 skill 加载两部分内容：

**a) 工具注入（语义召回）**

```
resolveSkillTools(skillId, userQuery, tenantId)
  → SkillEmbeddingIndex.searchTools(userQuery, topK=5)
  → 根据 userQuery 从该 skill 的工具池中语义召回 Top-5 工具
  → 注入到主 Agent 的 toolkit 中
```

标准 skill（无 `tools_json` 显式声明工具白名单）：从全量已启用的工具（租户级）中按用户 query 语义召回 Top-K。有 `tools_json` 声明的 skill：在声明的工具子集中做语义召回。

**b) Prompt 注入**

```java
// ReactExecutor 内部：
for (String skillId : routingResult.skillIds()) {
    String overlay = skillCatalogService.getOverlay(skillId);
    if (overlay != null) systemPrompt += "\n" + overlay;
    sandboxSessionLifecycle.mountSkill(sessionId, skillId);  // 挂载 /skills/{id}/ 物料
}
```

### 10.5 L3：Agent Catalog 注入（不注入工具给主 Agent）

当 `RoutingResult.agentIds` 非空时，**仅注入 Agent Catalog 摘要到 system prompt**，不把子 Agent 的工具加载到主 Agent 的 toolkit 中。主 Agent 通过 `spawn_subagent(agent_id=...)` 委派后，`SpawnSubagentTool` 按 `agent_id` 加载对应 agent 的 `agent_definition.tools_json`。

```java
// ReactExecutor 注入 Agent Catalog：
if (routingResult.hasAgents()) {
    systemPrompt += renderAgentCatalog(routingResult.agentIds());
    // 格式：
    // ## 可调用的专业智能体
    // | agent_id | 名称 | 擅长领域 |
    // |----------|------|----------|
    // | compliance-checker | 合规审查 | 制度检查、法规比对 |
    // 多个独立子任务可在同一轮并行 spawn
}

// SpawnSubagentTool 内部（子 Agent 创建时）：
AgentCatalogEntry entry = agentCatalogService.find(agentId);
toolIds = parseToolIds(entry.toolsJson());        // ← 该 agent 的专属工具
resolvedSystemOverlay = entry.systemPrompt();      // ← 该 agent 的 system prompt
skillIds = entry.skillIds();                        // ← 该 agent 绑定的 skill
```

**为什么不把子 Agent 的工具也注入给主 Agent？**
1. 主 Agent 不应该直接调用子 Agent 的领域工具——它应该委派给子 Agent
2. 子 Agent 的工具和主 Agent 的工具混在一起会让 LLM 困惑
3. 隔离上下文是 spawn_subagent 设计的核心目标

### 10.6 完整加载链（ReactExecutor.execute）

```java
// ReactExecutor.execute() 完整流程：

// 1. 基础工具（所有路径共用）
List<AgentTool> tools = new ArrayList<>();
tools.addAll(builtinTools());                                    // RAG + sandbox x6
tools.addAll(resolveSceneTools(scene, tenantId));                // chat-default / task-default
if (isMainAgent) tools.add(spawnSubagentTool);                   // 仅 MAIN

// 2. Skill 工具注入（含 agent+skill 共存场景）
if (routingResult.hasSkills()) {
    for (String skillId : routingResult.skillIds()) {
        // 2a. 语义召回 Top-K 工具（按用户 query 从该 skill 的工具池召回）
        tools.addAll(resolveSkillSemanticTools(skillId, userQuery, tenantId));
        // 2b. 挂载 /skills/{id}/ 物料
        sandboxSessionLifecycle.mountSkill(sessionId, skillId);
    }
}

// 3. 系统提示拼装
String systemPrompt = baseReactSystem(scene);
if (routingResult.hasSkills()) {
    for (String skillId : routingResult.skillIds()) {
        systemPrompt += skillCatalogService.getOverlay(skillId); // skill overlay
    }
}
if (routingResult.hasAgents()) {
    systemPrompt += renderAgentCatalog(routingResult.agentIds()); // Agent Catalog 表格
}
systemPrompt += modeOverlayReact();  // mode-overlay.react（含 orchestration 指导）

// 4. 构造 AgentRunRequest
AgentRunRequest main = AgentRunRequest.main(
    assembledCtx, userQuery, systemPrompt, tools, ...);

// 5. 主 Agent 运行中调用 spawn_subagent(agent_id="xxx") 时
//    → SpawnSubagentTool 加载 agent_definition.tools_json + systemPrompt + skills
//    → AgentRunRequest.sub(...)
```

### 10.7 安全模型（不变）

| 安全层 | 说明 |
|--------|------|
| **HITL** | 写工具确认由用户 `writeHitlMode` 控制 |
| **SandboxExecGuard** | 沙箱 exec 危险命令硬拒绝 |
| **PathJail** | 沙箱路径隔离 |
| **租户隔离** | 每租户独立默认工具集 + skill 召回池 |
| **Catalog 启用池** | 工具集结果与 `enabledIds` 求交 |

---

## 11. 组件处置清单

### 11.1 删除

| 组件 | 原因 |
|------|------|
| `ExecutionMode` 枚举 | 被 `ResourceType` 替代 |
| `ExecutionPreference` 枚举 | 显式绑定 + scene 替代 |
| `ForcedExecutionRouter` | 不再需要 |
| `ExecutionPlanRouter` | 被 `ResourceRouter` 替代 |
| `ExecutionPlan` | 被 `RoutingResult` 替代 |
| `ExecutionDispatcher` | 被 `ResourceDispatcher` 替代 |
| `PEER_COLLAB` 相关分支 | spawn_subagent 中心化替代 |
| `SkillDiscoveryService` | L3 直接输出 |
| `GUIDED` 相关逻辑 | 被深层语义兜底替代

### 11.2 修改

| 组件 | 改动 |
|------|------|
| `ReactExecutor` | **重大改动**：不加载 agent system prompt 做主 Agent；改为注入 Agent Catalog + 通用 ReAct 系统编排 |
| `UnifiedRuleRoutingPolicy` | 输出改为 `RoutingResult`；规则增加 `resourceType` 字段 |
| `LlmClassifierRoutingPolicy` | 输出支持多 agentIds/skillIds |
| `IntentRouter` | 新增 `classifyWithCandidates` 方法 |
| `DynamicToolkitFactory` | `resolveReactTools` → `resolveSceneTools(scene, tenantId)` |
| `ToolSetResolver` | 同上 |
| `ChatController` | 请求体增加 `scene`，删除 `executionPreference` |
| `ExecutionStreamContext` | `executionPlan` → `routingResult` |
| `ChatConversationEntity` | `executionMode` → `routingType` + `routingResourceId` |

### 11.3 新建

| 组件 | 说明 |
|------|------|
| `RoutingResult` + `ResourceType` | 核心路由输出（含 agentIds/skillIds 列表） |
| `RoutingAccumulator` | 跨层累积器 + L3 跳过判断 |
| `RoutingPolicyChain` | 累积 + 条件终止编排 |
| `ExplicitBindingRoutingPolicy` | L0 显式绑定 |
| `RuleBasedRoutingPolicy` | L1 规则匹配 |
| `SemanticRoutingPolicy` | L2 三路语义召回 |
| `LlmClassifierRoutingPolicy` | L3 LLM 分类 |
| `ResourceRouter` | 替代 `ExecutionPlanRouter` |
| `ResourceDispatcher` | 替代 `ExecutionDispatcher` |
| embedding 索引 ×3 | `WorkflowEmbeddingIndex` / `AgentEmbeddingIndex` / `SkillEmbeddingIndex` |

---

## 12. 实施阶段

### 阶段 R-1：数据模型 + 核心路由

- `RoutingResult` / `RoutingAccumulator` / `RoutingPolicyChain` 新建
- L0/L1 路由逻辑（累积模式）
- `ResourceDispatcher` 替代 `ExecutionDispatcher`
- 删除 `ExecutionMode` / `ExecutionPreference` / `ForcedExecutionRouter`
- **出口**：编译绿 + 单测（L0/L1 累积输出正确）

### 阶段 R-2：L2 语义召回 + L3

- 三路 embedding 索引
- `SemanticRoutingPolicy`（返回全量候选，不做 top-1 命中）
- `LlmClassifierRoutingPolicy`（多 agentIds/skillIds 输出）
- **出口**：编译绿 + 单测（分类型阈值 + L3 合并决策）

### 阶段 R-3：ReactExecutor 改造 + scene

- ReactExecutor 不加载 agent system prompt → 注入 Agent Catalog
- `DynamicToolkitFactory` 按 scene 加载
- scene 贯穿全链路
- **出口**：编译绿 + scene 过滤单测

### 阶段 R-4：DB 迁移 + 前端

- `chat_message` / `chat_conversation` 字段迁移
- 前端删除 `ExecutionModeSelector`，增加 GUIDED 交互
- Golden set 重写
- **出口**：Live 验收

### 阶段 R-5：清理

- 删除 `PEER_COLLAB` 残留
- 清理 `ExecutionMode` / `ExecutionPreference` 引用

---

## 13. 验收标准

| 维度 | 验收点 |
|------|--------|
| Pre-Routing | HITL/Plan 确认/续跑 等待态复用，不进入路由链 |
| L0 | `#workflow` → STOP（WORKFLOW 独占）；`$agent` + `@skill` → 共存收集 |
| L0 | `$agent-A $agent-B` → agentIds=[A,B]，全平权子 Agent |
| L1 | 规则命中 workflow → STOP |
| L2 | workflow ≥ 0.88 → STOP；agent ≥ 0.85 → 累积；skill ≥ 0.85 → 累积 |
| L3 快速分类 | L2 候选非空 → 传入候选清单，LLM 筛选/合并，~200-500ms |
| L3 深层语义兜底 | L2 候选全空 → 全量 L1 上下文 + 完整 Agent/Skill/Workflow Catalog → LLM 推断 |
| L3 深层语义兜底 | 指代词「那个/第一个/继续」→ 正确识别目标（结合对话历史） |
| L3 深层语义兜底 | 无明确目标 → agentIds/skillIds 为空，planMode=none → REACT 静默兜底 |
| planMode 判定 | L3 输出 planMode：认知步骤≥4或多级依赖→harness（Planner-Worker），否则→none（ReAct） |
| planMode=harness 分发 | ResourceDispatcher → PlannerHarnessExecutor（Chat/Task 由 scene 区分） |
| agent + skill 共存 | `RoutingResult(AGENT, agentIds=[...], skillIds=[...], planMode)`，planMode 决定 executor |
| ReactExecutor | AGENT 时不加载 agent system prompt → 注入 Agent Catalog |
| 回归 | 8 标杆 workflow 仍可执行；ReAct 仍可用；Plan-Workflow 直接 API 仍可用 |

---

## 14. 关键文件索引

| 文件 | 改动 | 说明 |
|------|------|------|
| `orchestrator/.../routing/RoutingResult.java` | 新建 | 替代 ExecutionPlan，含 agentIds/skillIds |
| `orchestrator/.../routing/policy/RoutingAccumulator.java` | 新建 | 跨层累积器 |
| `orchestrator/.../routing/policy/RoutingPolicyChain.java` | 新建 | 累积 + 条件终止 |
| `orchestrator/.../execution/ResourceDispatcher.java` | 新建 | AGENT/SKILL/REACT → ReactExecutor |
| `orchestrator/.../execution/ReactExecutor.java` | **重大修改** | 注入 Agent Catalog 而非加载 agent system prompt |
| `orchestrator/.../routing/ExecutionMode.java` | 删除 | |
| `orchestrator/.../routing/ExecutionPreference.java` | 删除 | |
| `orchestrator/.../routing/ForcedExecutionRouter.java` | 删除 | |
| `sunshine-ui/.../chat/ExecutionModeSelector.vue` | 删除 | |

---

## 15. 与相关 spec 的关系

| spec | 关系 |
|------|------|
| [multi-agent-unified-design](./2026-07-29-multi-agent-unified-design.md) | 前置：agent_definition 扩展 + spawn_subagent 中心化 |
| [task-workspace-codex](./2026-07-28-task-workspace-codex-design.md) | 并行：task 场景的 `scene` 过滤 |
| [prompt-ops-routing-catalog](./2026-07-20-prompt-ops-routing-catalog-design.md) | 修改：routing-rule 增加 `resourceType` + `scene` |
| [phase5-operation-openness-design.md](./phase5-operation-openness-design.md) | 命名隔离：本 spec `scene`（用户场景）与 llm-gateway `call_scene`（调用点，§0.2 v4 注记）互不冲突 |
| [2026-07-07-expert-consultation-design.md] | **废止**：PEER_COLLAB 模式被 spawn_subagent 中心化替代；多 Agent 协作统一到「通用 ReAct 主 Agent + spawn 子 Agent」模型 |

---

## 16. 风险与对策

| 风险 | 对策 |
|------|------|
| Agent Catalog 描述太简单，ReAct spawn 不当 | 运维优化 Agent Catalog 的 `description` + `擅长领域` |
| 单一 agent 场景下多一次 spawn 降效 | 通用 ReAct 可判断「简单，我自己答」，不强制 spawn |
| 用户写 `$agent-A` 期望它做主，结果成了子 | 前端展示「已识别专业智能体，交 AI 编排调用」 |
| **深层语义兜底耗时偏高**（500-800ms） | 仅 L2 空时触发（低频）；用全量上下文换准确率是合理权衡 |
| **深层语义兜底模型幻觉**（指代词判错目标） | 输出 confidence + 三段式决策控制采纳；错判→静默 REACT 兜底 |
| embedding 索引冷启动慢 | 异步预热 + 活跃租户优先 |
| workflow 语义召回误触发 | 0.88 高阈值 + L1 规则优先 |
| L3 候选过多稀释分类器 | Top-3 per type 上限（共 9 个） |
