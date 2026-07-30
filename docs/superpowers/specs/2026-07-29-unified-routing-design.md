# 统一资源路由设计（L0-L3 四层 + 三路语义召回）

> **状态**：设计稿（待评审）
> **日期**：2026-07-29
> **编号**：阶段四增量（路由层重构：ExecutionMode → ResourceType）
> **前置**：[multi-agent-unified-design](./2026-07-29-multi-agent-unified-design.md)（agent_definition 扩展 + scene 字段）· [workflow-structured-io](./2026-07-24-workflow-structured-io-design.md)（workflow 结构化 I/O 完成后 AgentNodeHandler 契约稳定）
> **一句话**：删除 `ExecutionMode` 路由体系，改为 L0-L3 四层统一资源路由，将 `workflow`/`agent`/`skill` 作为**平级资源类型**三路语义召回，`React` 收敛为唯一兜底执行内核；`Plan-Workflow` 保留代码但去掉路由入口。

---

## 0. 术语约定

| 术语 | 含义 |
|------|------|
| 资源（Resource） | workflow / agent / skill / react 四种可路由目标 |
| 路由（Routing） | 从用户输入到 `RoutingResult` 的决策过程 |
| 执行（Execution） | 从 `RoutingResult` 到 `Flux<StreamToken>` 的派发过程 |
| 兜底（Fallback） | 所有路由层无结果时，最终交给通用 ReAct |

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
| **ForcedExecutionRouter** | 用户手动选模式，与「资源路由」理念冲突——用户应该选资源（#/$/@），不是选模式 |
| **Plan-Workflow 在路由入口** | 动态 DAG 脆弱，LLM 产出不可靠，且用户确认后仍有退化风险 |
| **PEER_COLLAB 即将删除** | 被 [multi-agent-unified-design](./2026-07-29-multi-agent-unified-design.md) 的 spawn_subagent 中心化替代 |

### 1.2 目标

1. 删除 `ExecutionMode` 路由体系，改为 `ResourceType` 驱动
2. L0-L3 四层统一路由链，workflow / agent / skill 三路平等语义召回
3. `React` 收敛为唯一兜底执行内核（不再作为「模式」参与路由）
4. `Plan-Workflow` 保留代码但去掉路由入口（仅直接 API 调用）
5. 删除 `ForcedExecutionRouter`，删除 `PEER_COLLAB` 路由分支
6. `scene` 字段贯穿全链路，过滤资源

---

## 2. 路由层架构

### 2.1 四层路由链

```
用户输入 + scene ──→ L0 显式绑定
                      │  #workflow-id → RoutingResult(WORKFLOW, id, 1.0)
                      │  $agent-id    → RoutingResult(AGENT, id, 1.0)
                      │  @skill-id    → RoutingResult(SKILL, id, 1.0)
                      │
                      ├── L1 规则匹配（PromptCatalog routing-rule.*）
                      │   任意 resourceType，confidence 阈值 → 命中即出
                      │
                      ├── L2 三路语义召回（embedding）
                      │   workflow + agent + skill 并行检索
                      │   合并排序，按类型分阈值直接命中
                      │   未达阈值 → 候选列表注入 L3
                      │
                      └── L3 LLM 分类器（带候选列表）
                         在候选资源中做最终选择
                         无结果 → RoutingResult.reactFallback
```

### 2.2 核心数据模型

#### RoutingResult（替代 ExecutionPlan）

```java
package com.sunshine.orchestrator.routing;

import java.util.Map;

public record RoutingResult(
    ResourceType type,      // WORKFLOW / AGENT / SKILL / REACT
    String resourceId,      // workflowId / agentId / skillId / null（REACT 时）
    String scene,           // chat / task
    Map<String, String> params,
    String reason,          // 如 "l0:workflow" / "l1:rule:expense" / "l2:workflow:0.91"
    double confidence
) {
    public enum ResourceType {
        WORKFLOW,   // #workflow-id 或 L1/L2/L3 命中
        AGENT,      // $agent-id 或语义选中
        SKILL,      // @skill-id 或语义选中
        REACT       // 通用兜底
    }

    public static RoutingResult reactFallback(String scene, String reason) {
        return new RoutingResult(ResourceType.REACT, null, scene, Map.of(), reason, 1.0);
    }

    public boolean isWorkflow() { return type == ResourceType.WORKFLOW; }
    public boolean isAgent() { return type == ResourceType.AGENT; }
    public boolean isSkill() { return type == ResourceType.SKILL; }
    public boolean isReact() { return type == ResourceType.REACT; }
}
```

#### RoutingContext（替代现有 RoutingContext）

```java
package com.sunshine.orchestrator.routing.policy;

import com.sunshine.orchestrator.context.AssembledContext;
import java.util.HashMap;
import java.util.Map;

public record RoutingContext(
    String userMessage,
    String traceMessageId,
    String scene,           // "chat" | "task"
    AssembledContext memory
) {
    private static final Map<String, Object> attributes = new HashMap<>();

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }

    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    public boolean isChatScene() { return "chat".equals(scene); }
    public boolean isTaskScene() { return "task".equals(scene); }
}
```

### 2.3 RoutingPolicyChain

```java
package com.sunshine.orchestrator.routing.policy;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

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
        return routeRecursive(ctx, 0);
    }

    private Mono<RoutingResult> routeRecursive(RoutingContext ctx, int index) {
        if (index >= sorted.size()) {
            return Mono.just(RoutingResultUtils.reactFallback(ctx.scene(), "no_policy_match"));
        }
        return sorted.get(index).tryRoute(ctx)
            .flatMap(opt -> opt.<Mono<RoutingResult>>map(Mono::just)
                .orElseGet(() -> routeRecursive(ctx, index + 1)));
    }
}
```

---

## 3. L0：显式绑定路由

用户输入中的 `#workflow-id` / `$agent-id` / `@skill-id` 为最高优先级，置信度 1.0 直通。

```java
@Component
@RequiredArgsConstructor
public class ExplicitBindingRoutingPolicy implements RoutingPolicy {
    private final WorkflowBindingParser workflowBindingParser;
    private final AgentBindingParser agentBindingParser;
    private final SkillBindingParser skillBindingParser;

    @Override public int order() { return 0; }

    @Override
    public Mono<Optional<RoutingResult>> tryRoute(RoutingContext ctx) {
        String msg = ctx.userMessage();

        if (ctx.isChatScene()) {
            var wf = workflowBindingParser.resolve(msg);
            if (wf.bound()) {
                return Mono.just(Optional.of(new RoutingResult(
                    ResourceType.WORKFLOW, wf.workflowId(), ctx.scene(),
                    Map.of(), "l0:workflow", 1.0)));
            }
        }

        var agent = agentBindingParser.resolve(msg);
        if (agent.bound()) {
            return Mono.just(Optional.of(new RoutingResult(
                ResourceType.AGENT, agent.agentId(), ctx.scene(),
                Map.of(), "l0:agent", 1.0)));
        }

        var skill = skillBindingParser.resolve(msg);
        if (skill.bound()) {
            return Mono.just(Optional.of(new RoutingResult(
                ResourceType.SKILL, skill.skillId(), ctx.scene(),
                Map.of(), "l0:skill", 1.0)));
        }

        return Mono.just(Optional.empty());
    }
}
```

**约束**：`#workflow-id` 仅在 `scene=chat` 场景生效（task 场景走 agent/skill 路由）。

---

## 4. L1：规则匹配路由

保持现有 `UnifiedRuleRoutingPolicy` 核心逻辑不变，Catalog `routing-rule.*` 规则匹配。改动点：

1. **输出改为 `RoutingResult`**（不再返回 `ExecutionPlan`）
2. **规则增加 `resourceType` 字段**：明确命中后返回 `WORKFLOW` / `AGENT` / `SKILL`
3. **confidence 阈值**：规则命中 confidence 由规则定义，默认 0.95

```java
@Component
@RequiredArgsConstructor
public class RuleBasedRoutingPolicy implements RoutingPolicy {
    private final UnifiedRuleEngine ruleEngine;

    @Override public int order() { return 10; }

    @Override
    public Mono<Optional<RoutingResult>> tryRoute(RoutingContext ctx) {
        return ruleEngine.match(ctx.userMessage(), ctx.scene())
            .filter(match -> match.confidence() >= 0.85)
            .map(match -> Optional.of(new RoutingResult(
                match.resourceType(),
                match.resourceId(),
                ctx.scene(),
                Map.of(),
                "l1:rule:" + match.ruleId(),
                match.confidence()
            )))
            .defaultIfEmpty(Optional.empty());
    }
}
```

---

## 5. L2：三路语义召回

### 5.1 三路并行检索

```java
@Component
@RequiredArgsConstructor
public class SemanticRoutingPolicy implements RoutingPolicy {
    private final AgentEmbeddingIndex agentIndex;
    private final SkillEmbeddingIndex skillIndex;
    private final WorkflowEmbeddingIndex workflowIndex;

    @Override public int order() { return 20; }

    @Override
    public Mono<Optional<RoutingResult>> tryRoute(RoutingContext ctx) {
        String query = ctx.userMessage();
        String scene = ctx.scene();

        Mono<List<ScoredResource>> agentCandidates =
            agentIndex.search(query, 3, scene)
                .map(list -> list.stream()
                    .map(e -> new ScoredResource(ResourceType.AGENT, e.id(), e.score()))
                    .toList());

        Mono<List<ScoredResource>> skillCandidates =
            skillIndex.search(query, 3, scene)
                .map(list -> list.stream()
                    .map(e -> new ScoredResource(ResourceType.SKILL, e.id(), e.score()))
                    .toList());

        Mono<List<ScoredResource>> workflowCandidates =
            workflowIndex.search(query, 3, scene)
                .map(list -> list.stream()
                    .map(e -> new ScoredResource(ResourceType.WORKFLOW, e.id(), e.score()))
                    .toList());

        return Mono.zip(agentCandidates, skillCandidates, workflowCandidates)
            .map(tuple -> {
                List<ScoredResource> merged = new ArrayList<>();
                merged.addAll(tuple.getT1());
                merged.addAll(tuple.getT2());
                merged.addAll(tuple.getT3());
                merged.sort((a, b) -> Double.compare(b.score(), a.score()));

                if (merged.isEmpty()) {
                    return Optional.<RoutingResult>empty();
                }

                ScoredResource top = merged.get(0);
                double threshold = directHitThreshold(top.type());
                if (top.score() >= threshold) {
                    return Optional.of(new RoutingResult(
                        top.type(), top.id(), scene, Map.of(),
                        "l2:" + top.type().name().toLowerCase() + ":" + top.score(),
                        top.score()));
                }

                // 未达阈值 → 候选列表注入 L3
                ctx.setAttribute("semanticCandidates", merged);
                return Optional.<RoutingResult>empty();
            });
    }

    /**
     * 分类型直接命中阈值。
     * workflow 误路由成本高（确定性 DAG 整跑一轮），要求更高置信。
     */
    private double directHitThreshold(ResourceType type) {
        return switch (type) {
            case WORKFLOW -> 0.88;
            case AGENT, SKILL -> 0.85;
            default -> 0.85;
        };
    }

    public record ScoredResource(ResourceType type, String id, double score) {}
}
```

### 5.2 workflow embedding 索引

**数据源**：workflow-manager DB `workflow_definition` 表（name + description + 触发示例）。

**索引生命周期**：
- 发布/更新 → 重建该 workflow 的 embedding 向量
- 禁用/删除 → 从索引中移除
- 服务启动 → 全量重建（与 agent/skill 索引一致）

**触发示例**：8 标杆 workflow 种子数据补齐 `trigger_examples` 字段（如 `"帮我报销"、"走一下审批流程"`），作为 embedding 输入的一部分。

### 5.3 降级路径

embedding 服务不可用时，L2 返回 `Optional.empty()`，不阻塞路由链，直接进入 L3。

---

## 6. L3：LLM 分类器（带候选列表）

### 6.1 设计决策：不打断用户

L3 是最后一层路由，低置信度时**不打断用户**，而是交给 REACT 执行层自行澄清。理由：

| 决策因素 | 说明 |
|---------|------|
| **REACT 自己会澄清** | REACT 执行中是自由推理循环，遇到歧义可通过 `react-request-decision`（4.7.7 spec）主动向用户出选择题。澄清职责在执行层，不在路由层 |
| **闲聊/通用问答不匹配是正常的** | 用户说"今天天气不错"或"Python 怎么读 CSV"，L3 应返回 `no_match`，直接走 REACT 兜底正常回答。这不是"低置信度"，而是"明确不匹配预置资源" |
| **LLM 自评 confidence 不可靠** | 同一个 prompt 两次可能输出 0.5 和 0.9，无严格概率意义。用这个数字做"是否打断用户"的决策依据不可靠 |
| **打断成本高** | 用户一句话被路由层反问，需等待回复、再分类、再执行。而 REACT 在推理中出选择题时已有上下文（如已 read 文件、理解项目结构），选择题更有针对性 |

### 6.2 三段式决策

L3 分类器输出后，按 confidence 分三段处理：

| 分段 | confidence | 行为 |
|------|-----------|------|
| `no_match` | N/A | 分类器明确判断无匹配资源 → 返回 empty，走 REACT 兜底 |
| 低置信度 | 0.5 ≤ c < 0.8 | 执行选中的资源，但把 L2 候选列表注入 `params.__candidates`，供 REACT 首轮推理中参考，必要时自行判断是否切换方向 |
| 高置信度 | c ≥ 0.8 | 直接执行，不注入额外信息 |
| 极低置信度 | c < 0.5 | 视为分类器不确定 → 返回 empty，走 REACT 兜底（安全网，防止 LLM 乱选） |

```java
@Component
@RequiredArgsConstructor
public class LlmClassifierRoutingPolicy implements RoutingPolicy {
    private final IntentRouter intentRouter;

    @Override public int order() { return 30; }

    @Override
    public Mono<Optional<RoutingResult>> tryRoute(RoutingContext ctx) {
        @SuppressWarnings("unchecked")
        List<SemanticRoutingPolicy.ScoredResource> candidates =
            ctx.getAttribute("semanticCandidates");

        return intentRouter.classifyWithCandidates(
                ctx.userMessage(), candidates, ctx.scene(), ctx.memory())
            .map(decision -> {
                // no_match 或极低置信度 → 不给 REACT 任何偏好，自由执行
                if (decision.isNoMatch() || decision.confidence() < 0.5) {
                    return Optional.<RoutingResult>empty();
                }
                // 中等置信度 → 执行选中资源，但附候选列表供 REACT 参考
                if (decision.confidence() < 0.8) {
                    return Optional.of(new RoutingResult(
                        decision.type(),
                        decision.resourceId(),
                        ctx.scene(),
                        Map.of("__candidates", toJson(candidates)),
                        "l3:low:" + decision.reason(),
                        decision.confidence()));
                }
                // 高置信度 → 直接执行
                return Optional.of(new RoutingResult(
                    decision.type(),
                    decision.resourceId(),
                    ctx.scene(),
                    Map.of(),
                    "l3:" + decision.reason(),
                    decision.confidence()));
            })
            .defaultIfEmpty(Optional.empty());
    }
}
```

### 6.3 分类器 prompt 要点

- 输入包含候选资源清单（含 type + id + name + description + score）
- 要求输出 `{type, resourceId, confidence, reason}` JSON
- **候选资源都不匹配时返回 `{type: "REACT", resourceId: null, confidence: 0, reason: "no_match"}`**——这是明确的不匹配信号，不是低置信度
- 场景约束：`scene=task` 时不选 `WORKFLOW`（task 场景主要走 agent/skill）
- `confidence` 字段要求 LLM 输出 0.0–1.0 的浮点数，表示对选择的确定程度

---

## 7. 资源派发器

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
    private final PlanWorkflowExecutor planWorkflowExecutor; // 保留，仅直接 API 调用

    public Flux<StreamToken> execute(ExecutionStreamContext ctx) {
        RoutingResult result = ctx.routingResult();

        // Plan-Workflow 仅直接 API 调用路径（__mode=plan-workflow），不经过路由
        if (result != null && "plan-workflow".equals(result.params().get("__mode"))) {
            return planWorkflowExecutor.execute(ctx);
        }

        return switch (result != null ? result.type() : ResourceType.REACT) {
            case WORKFLOW -> workflowExecutor.execute(ctx);
            case AGENT, SKILL, REACT -> reactExecutor.execute(ctx);
        };
    }
}
```

**关键设计**：
- `AGENT` / `SKILL` / `REACT` 三种 RoutingResult 都走 `ReactExecutor`——因为它们的执行内核统一为 `AgentRuntime.run`（或 ReAct 兜底）
- `AGENT` 命中时，`ReactExecutor` 内部根据 `resourceId` 加载 agent 配置（system prompt / tools / skills），构造 `AgentRunRequest`
- `SKILL` 命中时，`ReactExecutor` 内部根据 `resourceId` 加载 skill overlay，注入 tool 白名单
- `REACT` 兜底时，走通用 ReAct 配置

---

## 8. 组件处置清单

### 8.1 删除

| 组件 | 原因 |
|------|------|
| `ExecutionMode` 枚举 | 被 `ResourceType` 替代 |
| `ExecutionPreference` 枚举 | 显式绑定 + scene 约束替代 |
| `ForcedExecutionRouter` | 不再需要用户手动选模式 |
| `ExecutionPlanRouter` | 被 `ResourceRouter` 替代 |
| `ExecutionPlan` | 被 `RoutingResult` 替代 |
| `ExecutionDispatcher` | 被 `ResourceDispatcher` 替代 |
| `PEER_COLLAB` 路由分支 | 被 spawn_subagent 中心化替代 |
| `SkillDiscoveryService`（orchestrator） | L3 直接输出 skillId，不再需要二次校验 |

### 8.2 修改

| 组件 | 改动 |
|------|------|
| `UnifiedRuleRoutingPolicy` | 输出改为 `RoutingResult`；规则增加 `resourceType` 字段 |
| `LlmClassifierRoutingPolicy` | 入参改为候选资源列表（含 type）；输出改为 `RoutingResult` |
| `IntentRouter` | 新增 `classifyWithCandidates` 方法 |
| `DynamicToolkitFactory` | 根据 `RoutingResult`（AGENT/SKILL/REACT）加载不同工具集 |
| `ToolSetResolver` | 增加 `scene` 参数 |
| `ChatController` | 请求体增加 `scene` 字段；删除 `executionPreference` |
| `ExecutionStreamContext` | `executionPlan` → `routingResult` |
| `ChatConversationEntity` | `executionMode` → `routingType` + `routingResourceId` |
| `chat_message` 表 | `execution_mode` → `routing_type` + `routing_resource_id` |
| `routing-golden-set.md` | 全量改写为资源路由 golden set |

### 8.3 新建

| 组件 | 说明 |
|------|------|
| `RoutingResult` + `ResourceType` | 核心路由输出 |
| `RoutingContext`（新） | 路由上下文，含 `scene` + `attributes` |
| `RoutingPolicyChain` | 策略链编排 |
| `ExplicitBindingRoutingPolicy` | L0 显式绑定（合并 `#`/`$`/`@`） |
| `RuleBasedRoutingPolicy` | L1 规则匹配（重构自 `UnifiedRuleRoutingPolicy`） |
| `SemanticRoutingPolicy` | L2 三路语义召回 |
| `WorkflowEmbeddingIndex` | workflow embedding 索引 |
| `AgentEmbeddingIndex` | agent embedding 索引 |
| `SkillEmbeddingIndex` | skill embedding 索引 |
| `ResourceRouter` | 替代 `ExecutionPlanRouter` |
| `ResourceDispatcher` | 替代 `ExecutionDispatcher` |

### 8.4 保留（不改动）

| 组件 | 说明 |
|------|------|
| `PlanWorkflowExecutor` | 保留代码，仅直接 API 调用（`__mode=plan-workflow`），不经过路由 |
| `PlanWorkflowExecutor` 相关全套类 | `PlanValidator` / `PlanAnswerPromptAssembler` / `NodeRetryExecutor` 等均保留 |
| `WorkflowExecutor` | 路由命中 WORKFLOW → 走此执行器 |
| `ReactExecutor` | 路由命中 AGENT/SKILL/REACT → 走此执行器 |

---

## 9. scene 字段贯穿全链路

### 9.1 定义

| scene | 含义 | 典型入口 |
|-------|------|---------|
| `chat` | 对话场景 | 主 Chat 页面 |
| `task` | 编码任务场景 | 工作区（agent_workspace）创建的任务会话 |

### 9.2 过滤规则

| 资源类型 | scene=chat | scene=task |
|----------|-----------|-----------|
| workflow | ✅ 全部可用 | 仅 `scene in (task, both)` 的 workflow |
| agent | ✅ 全部可用 | 仅 `scene in (task, both)` 的 agent |
| skill | ✅ 全部可用 | 仅 `scene in (task, both)` 的 skill |
| react | ✅ 兜底 | ✅ 兜底 |

### 9.3 数据模型扩展

| 表 | 新增字段 | 说明 |
|----|---------|------|
| `agent_definition` | `scene` VARCHAR(16) DEFAULT 'both' | chat / task / both |
| `skill_definition`（或 `skill_versions`） | `scene` VARCHAR(16) DEFAULT 'both' | chat / task / both |
| `workflow_definition` | `scene` VARCHAR(16) DEFAULT 'chat' | 主要 chat，少数 both |
| `tool_catalog` | `scene` VARCHAR(16) DEFAULT 'both' | 工具集过滤 |
| `routing-rule`（prompt-manager） | `scene` VARCHAR(16) DEFAULT 'both' | 规则过滤 |

---

## 10. 前端适配

### 10.1 删除执行模式选择器

Chat 底栏 `ExecutionModeSelector` 删除。用户不再手动选模式，改为：
- 显式绑定：输入 `#workflow-id` / `$agent-id` / `@skill-id`（补全提示保留）
- 隐式路由：系统自动 L0→L1→L2→L3 决策

### 10.2 新增用户可见信息

- 路由结果提示：消息区顶部显示「已匹配到 xx 工作流」/「已分配 xx 智能体」/「已加载 xx 技能」（可关闭）
- 路由失败时：「未匹配到特定资源，由通用 AI 助手处理」

### 10.3 请求体变更

```diff
{
  "message": "...",
- "executionPreference": "AUTO",
- "writeHitlMode": "smart",
+ "scene": "chat",
+ "writeHitlMode": "smart"
}
```

---

## 11. 工具加载策略

| 路由结果 | 工具加载策略 |
|---------|-------------|
| `WORKFLOW` | workflow 定义中的工具集（`PlanJson` 节点 `params.tool`） |
| `AGENT` | `agent_definition.tools_json` + `skills.tools_json`（agent 绑定的 skill） |
| `SKILL` | `skill_definition` 的 `tools_json` 显式声明 + 通用工具 embedding 召回（Top-K 注入） |
| `REACT` | 通用工具集（租户默认 toolset）+ embedding 召回 Top-K 工具 |

**标准 skill（无 `tools_json`）**：ReAct 通用工具集 + embedding 召回 Top-K 工具，不加载全量工具。

---

## 12. 实施阶段

### 阶段 R-1：数据模型 + 核心路由

- `RoutingResult` / `ResourceType` / `RoutingContext` 新建
- `RoutingPolicyChain` + `ExplicitBindingRoutingPolicy` 新建
- `RuleBasedRoutingPolicy` 重构（`UnifiedRuleRoutingPolicy` → 新输出）
- `ResourceRouter` 替代 `ExecutionPlanRouter`
- `ResourceDispatcher` 替代 `ExecutionDispatcher`
- 删除 `ExecutionMode` / `ExecutionPreference` / `ForcedExecutionRouter`
- **出口闸门**：编译绿 + 单测（L0/L1 路由输出正确）

### 阶段 R-2：三路语义召回

- `AgentEmbeddingIndex` / `SkillEmbeddingIndex` / `WorkflowEmbeddingIndex` 新建
- `SemanticRoutingPolicy` 新建
- `LlmClassifierRoutingPolicy` 改造（候选列表输入）
- `IntentRouter.classifyWithCandidates` 新增
- **出口闸门**：编译绿 + 单测（三路检索 + 分类型阈值 + L3 候选）

### 阶段 R-3：scene 贯穿 + 工具加载

- `agent_definition` / `skill_definition` / `workflow_definition` / `tool_catalog` / `routing-rule` 增加 `scene` 字段
- `DynamicToolkitFactory` 按 `RoutingResult` 加载工具
- `ToolSetResolver` 增加 `scene` 参数
- embedding 索引按 `scene` 过滤
- **出口闸门**：编译绿 + scene 过滤单测

### 阶段 R-4：DB 迁移 + 前端

- `chat_message` 表 `execution_mode` → `routing_type` + `routing_resource_id`
- `ChatConversationEntity` 对应字段变更
- `ChatController` 请求体 `scene` 替代 `executionPreference`
- 前端删除 `ExecutionModeSelector`，增加路由结果提示
- Golden set 重写
- **出口闸门**：Live 验收（路由 golden set 全过）

### 阶段 R-5：清理

- 删除 `PEER_COLLAB` 相关路由分支（已在 multi-agent-unified-design 中完成）
- 删除 `SkillDiscoveryService`
- 清理 `ExecutionMode` / `ExecutionPreference` 引用残留
- **出口闸门**：编译绿 + 全量回归

---

## 13. 验收标准

| 维度 | 验收点 | 方式 |
|------|--------|------|
| L0 | `#workflow-id` / `$agent-id` / `@skill-id` 直通 | 单测 + live |
| L1 | Catalog 规则命中 workflow/agent/skill | 单测 + live |
| L2 | 三路语义召回 > 阈值直接命中 | 单测 |
| L2 | workflow 阈值 0.88 vs agent/skill 0.85 | 单测 |
| L2 | embedding 不可用 → 降级到 L3 | 单测 |
| L3 | LLM 分类器在候选列表中正确选择（高置信度） | 单测 + live |
| L3 | `no_match` → 返回 empty，走 REACT 兜底（不打断用户） | 单测 |
| L3 | confidence < 0.5 → 返回 empty，走 REACT 兜底（安全网） | 单测 |
| L3 | 0.5 ≤ confidence < 0.8 → 执行选中资源 + 注入 `__candidates` 供 REACT 参考 | 单测 |
| L3 | 候选列表为空 → fallback REACT | 单测 |
| L3 | 闲聊/通用问答（"今天天气不错"）→ no_match → REACT 正常回答 | 单测 + live |
| Fallback | 四层无结果 → REACT 兜底 | 单测 + live |
| scene | chat/task 分别过滤资源 | 单测 |
| 前端 | 无执行模式选择器；路由结果提示 | live |
| 回归 | 8 标杆 workflow 仍可执行 | live |
| 回归 | ReAct 仍可正常使用 | live |
| 回归 | Plan-Workflow 直接 API 调用仍可用 | live |

---

## 14. 关键文件索引

| 文件 | 改动类型 | 说明 |
|------|---------|------|
| orchestrator/.../routing/RoutingResult.java | 新建 | 替代 ExecutionPlan |
| orchestrator/.../routing/ResourceType.java | 新建 | 替代 ExecutionMode |
| orchestrator/.../routing/ResourceRouter.java | 新建 | 替代 ExecutionPlanRouter |
| orchestrator/.../routing/policy/RoutingContext.java | 重写 | 含 scene + attributes |
| orchestrator/.../routing/policy/RoutingPolicyChain.java | 新建 | 策略链编排 |
| orchestrator/.../routing/policy/ExplicitBindingRoutingPolicy.java | 新建 | L0 显式绑定 |
| orchestrator/.../routing/policy/RuleBasedRoutingPolicy.java | 新建 | L1 规则匹配 |
| orchestrator/.../routing/policy/SemanticRoutingPolicy.java | 新建 | L2 三路语义召回 |
| orchestrator/.../routing/policy/LlmClassifierRoutingPolicy.java | 重写 | 候选列表模式 |
| orchestrator/.../routing/embedding/WorkflowEmbeddingIndex.java | 新建 | workflow embedding |
| orchestrator/.../routing/embedding/AgentEmbeddingIndex.java | 新建 | agent embedding |
| orchestrator/.../routing/embedding/SkillEmbeddingIndex.java | 新建 | skill embedding |
| orchestrator/.../execution/ResourceDispatcher.java | 新建 | 替代 ExecutionDispatcher |
| orchestrator/.../execution/ExecutionStreamContext.java | 修改 | routingResult 替代 executionPlan |
| orchestrator/.../controller/ChatController.java | 修改 | scene 替代 executionPreference |
| orchestrator/.../routing/ExecutionPlanRouter.java | 删除 | 被 ResourceRouter 替代 |
| orchestrator/.../routing/ForcedExecutionRouter.java | 删除 | 不再需要 |
| orchestrator/.../routing/ExecutionMode.java | 删除 | 被 ResourceType 替代 |
| orchestrator/.../routing/ExecutionPreference.java | 删除 | 不再需要 |
| orchestrator/.../routing/ExecutionPlan.java | 删除 | 被 RoutingResult 替代 |
| orchestrator/.../execution/ExecutionDispatcher.java | 删除 | 被 ResourceDispatcher 替代 |
| orchestrator/.../skill/SkillDiscoveryService.java | 删除 | L3 直接输出 |
| docker/mysql/init/11-sunshine-orchestrator.sql | 修改 | 字段迁移 |
| docker/mysql/init/12-sunshine-skill-manager.sql | 修改 | scene 字段 |
| docker/mysql/init/13-sunshine-workflow-manager.sql | 修改 | scene 字段 + 触发示例 |
| sunshine-ui/.../chat/ChatView.vue | 修改 | 删除执行模式选择器 |
| sunshine-ui/.../chat/ExecutionModeSelector.vue | 删除 | 不再需要 |
| docs/routing/routing-golden-set.md | 重写 | 资源路由 golden set |

---

## 15. 与相关 spec 的关系

| spec | 关系 |
|------|------|
| [multi-agent-unified-design](./2026-07-29-multi-agent-unified-design.md) | 前置：agent_definition 扩展 + scene 字段 + spawn_subagent 中心化 |
| [workflow-structured-io](./2026-07-24-workflow-structured-io-design.md) | 前置：AgentNodeHandler I/O 契约稳定 |
| [task-workspace-codex](./2026-07-28-task-workspace-codex-design.md) | 并行：task 场景的 `scene` 过滤依赖本设计 |
| [prompt-ops-routing-catalog](./2026-07-20-prompt-ops-routing-catalog-design.md) | 修改：routing-rule 增加 `resourceType` + `scene` 字段 |
| [remove-simple-llm-mode](./2026-07-17-remove-simple-llm-mode-design.md) | 已完成：simple-llm 已删除，本设计是最终形态 |

---

## 16. 风险与对策

| 风险 | 对策 |
|------|------|
| embedding 索引冷启动慢 | 异步预热 + 启动时只加载当前活跃租户的索引 |
| workflow 语义召回误触发 | 0.88 高阈值 + L1 规则优先 + Grafana 按 resourceType 统计误召回率 |
| L3 候选过多稀释分类器注意力 | Top-3 per type 上限（共 9 个候选） |
| 前端用户习惯变更 | 保留 `#`/`$`/`@` 补全提示，路由结果提示可关闭 |
| 存量 `execution_mode` 字段兼容 | 迁移脚本一次性转换，不保留旧字段 |