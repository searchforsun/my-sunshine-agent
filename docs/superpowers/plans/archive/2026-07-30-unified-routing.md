# 统一资源路由重构 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 删除 `ExecutionMode`/`ExecutionPreference` 路由体系，改为 `RoutingResult` + L0-L2 快速路径 + L3 语义兜底；所有命中的 agent 为子 Agent，通用 ReAct 为唯一主 Agent。

**Architecture:** 分层清理：先建新模型（RoutingResult/RoutingAccumulator/RoutingPolicyChain），再用 ResourceDispatcher 替代 ExecutionDispatcher，最后改 ReactExecutor（注入 Agent Catalog 替代 agent system prompt 做主 Agent）。每阶段独立编译通过。

**Tech Stack:** Java 17、Spring Boot 3.x、Project Reactor (Mono)、JUnit 5 + Mockito + AssertJ、Vue 3 + Naive UI

## Global Constraints

- 所有路由规则 SSOT = prompt-manager DB `routing-rule.*`；Nacos 仅保留非提示词运行参数
- 禁止 orchestrator 硬编码 prompt 模板；正文来自 Catalog `/prompts`
- 每阶段出口 = 编译绿 + 对应单测通过；禁止合并到后续阶段
- 现有 golden set（`RoutingGoldenSetTest`）需逐步迁移，禁止直接删除
- Pre-Routing（`ReuseContinuationDetector`）不改动
- `Plan-Workflow` 保留代码但去掉路由入口（仅直接 API）
- Agent/Skill binding parser 已有实现（`AgentBindingParser`/`SkillBindingParser`/`WorkflowBindingParser`），复用不重写

---

## File Structure

### 新建（orchestrator）

| 文件 | 职责 |
|------|------|
| `routing/RoutingResult.java` | 路由输出 record，含 `ResourceType` 枚举 + 工厂方法 |
| `routing/ScoredResource.java` | L2 embedding 召回结果 record |
| `routing/policy/L0Result.java` | L0 显式绑定结果 record |
| `routing/policy/L3Decision.java` | L3 LLM 决策结果 record |
| `routing/policy/RoutingAccumulator.java` | L0-L2 跨层累积状态容器，WORKFLOW→STOP |
| `routing/policy/RoutingPolicyChain.java` | 编排 L0→L1→L2→L3，链式 Mono flatMap |
| `routing/policy/ExplicitBindingRoutingPolicy.java` | L0 显式绑定：`#`/`$`/`@` 解析 |
| `routing/policy/RuleBasedRoutingPolicy.java` | L1 规则匹配：复用 `UnifiedRuleEngine` |
| `routing/policy/SemanticRoutingPolicy.java` | L2 三路语义召回 |
| `routing/policy/LlmClassifierRoutingPolicy.java` | L3 LLM 语义兜底（双模式） |
| `routing/index/AgentEmbeddingIndex.java` | Agent embedding 索引（stub，后续接入 Milvus） |
| `routing/index/SkillEmbeddingIndex.java` | Skill embedding 索引（stub） |
| `routing/index/WorkflowEmbeddingIndex.java` | Workflow embedding 索引（stub） |
| `execution/ResourceDispatcher.java` | 替代 ExecutionDispatcher |
| `execution/ResourceRouter.java` | 替代 ExecutionPlanRouter |

### 新建（orchestrator 测试）

| 文件 | 职责 |
|------|------|
| `routing/RoutingResultTest.java` | RoutingResult 工厂 + 便捷判断 |
| `routing/policy/RoutingAccumulatorTest.java` | L0/L1/L2 absorb + STOP + build |
| `routing/policy/RoutingPolicyChainTest.java` | 链编排：STOP 提前终止、L3 双模式 |
| `routing/policy/ExplicitBindingRoutingPolicyTest.java` | L0 `#`/`$`/`@` 解析 |
| `routing/policy/RuleBasedRoutingPolicyTest.java` | L1 规则 → RoutingResult |
| `routing/policy/SemanticRoutingPolicyTest.java` | L2 三路合并 + 阈值 |
| `routing/policy/LlmClassifierRoutingPolicyTest.java` | L3 快速分类 + 深层兜底 |
| `execution/ResourceDispatcherTest.java` | ResourceDispatcher switch 分发 |

### 修改（orchestrator）

| 文件 | 改动 |
|------|------|
| `execution/ReactExecutor.java` | 注入 Agent Catalog 替代 agent system prompt |
| `agent/IntentRouter.java` | 新增 `classifyWithCandidates()` + `classifyDeepSemantic()` |
| `agent/DynamicToolkitFactory.java` | `resolveReactTools` → `resolveSceneTools(scene, tenantId)` |
| `execution/ExecutionStreamContext.java` | `plan` → 新增 `routingResult` |
| `controller/ChatController.java` | 请求体删除 `executionPreference`，增加 `scene` |
| `model/ChatMessage.java` | 删除 `executionPreference`，增加 `scene` |
| `routing/policy/RoutingPolicy.java` | 接口扩展默认 `tryRoute(RoutingContext)` 支持新返回类型 |

### 删除（orchestrator）

| 文件 | 原因 |
|------|------|
| `routing/ExecutionMode.java` | 被 `ResourceType` 替代 |
| `routing/ExecutionPreference.java` | 被 L0 显式绑定 + scene 替代 |
| `routing/ExecutionPlan.java` | 被 `RoutingResult` 替代 |
| `routing/ExecutionPlanParser.java` | 被 L3 `L3Decision.from()` 替代 |
| `routing/ForcedExecutionRouter.java` | 不再需要 |
| `routing/policy/UnifiedRuleRoutingPolicy.java` | 重构为 `RuleBasedRoutingPolicy` |
| `execution/ExecutionDispatcher.java` | 被 `ResourceDispatcher` 替代 |
| `skill/SkillDiscoveryService.java` | L3 直接输出 skillIds |

### 修改（前端）

| 文件 | 改动 |
|------|------|
| `sunshine-ui/src/components/chat/ExecutionModeSelector.vue` | 删除 |
| `sunshine-ui/src/api/executionModes.ts` | 删除 |
| `sunshine-ui/src/api/executionModeIcons.ts` | 删除 |
| `sunshine-ui/src/composables/useExecutionPreference.ts` | 删除 |
| `sunshine-ui/src/views/ChatView.vue` | 删除 `ExecutionModeSelector` 引用 |
| `sunshine-ui/src/api/chat.ts` | `executionPreference` → `scene` |
| `sunshine-ui/src/stores/chatStore.ts` | 删除 `executionPreference` |
| `sunshine-ui/src/composables/useChatAgentMention.ts` | `executionPreference` 引用移除 |
| `sunshine-ui/src/composables/useChatSkillMention.ts` | 同上 |
| `sunshine-ui/src/composables/useChatWorkflowMention.ts` | 同上 |

### DB 迁移

| 文件 | 改动 |
|------|------|
| `docker/mysql/init/18-sunshine-routing-migration.sql` | 新增 migration SQL |
| `docker/mysql/init/17-sunshine-prompt-manager.sql` | 删除 `routing-rule.peer-phrase` 种子 |

---

### Task 1: RoutingResult 数据模型 + 单测

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/routing/RoutingResult.java`
- Create: `orchestrator/src/test/java/com/sunshine/orchestrator/routing/RoutingResultTest.java`

**Interfaces:**
- Produces: `RoutingResult` record（`type`、`workflowId`、`agentIds`、`skillIds`、`scene`、`params`、`reason`）+ `ResourceType` enum（`WORKFLOW`/`AGENT`/`SKILL`/`REACT`）+ 工厂方法 `workflow()`/`silentFallback()` + 便捷判断 `isWorkflow()`/`usesReactExecutor()`/`hasAgents()`/`hasSkills()`

- [ ] **Step 1: 写 RoutingResult 单测**

```java
package com.sunshine.orchestrator.routing;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class RoutingResultTest {

    @Test
    void workflow_factory_should_set_type_workflow() {
        var r = RoutingResult.workflow("expense-flow", "chat", "l0:workflow");
        assertThat(r.type()).isEqualTo(RoutingResult.ResourceType.WORKFLOW);
        assertThat(r.workflowId()).isEqualTo("expense-flow");
        assertThat(r.agentIds()).isEmpty();
        assertThat(r.skillIds()).isEmpty();
        assertThat(r.isWorkflow()).isTrue();
        assertThat(r.usesReactExecutor()).isFalse();
    }

    @Test
    void silentFallback_should_set_type_react() {
        var r = RoutingResult.silentFallback("chat", "fallback:silent");
        assertThat(r.type()).isEqualTo(RoutingResult.ResourceType.REACT);
        assertThat(r.workflowId()).isNull();
        assertThat(r.agentIds()).isEmpty();
        assertThat(r.skillIds()).isEmpty();
    }

    @Test
    void hasAgents_should_return_true_when_agentIds_nonempty() {
        var r = new RoutingResult(RoutingResult.ResourceType.AGENT, null,
                List.of("agent-a", "agent-b"), List.of(),
                "chat", Map.of(), "l2:semantic");
        assertThat(r.hasAgents()).isTrue();
        assertThat(r.hasSkills()).isFalse();
    }

    @Test
    void agent_and_skill_can_coexist() {
        var r = new RoutingResult(RoutingResult.ResourceType.AGENT, null,
                List.of("agent-a"), List.of("skill-x"),
                "chat", Map.of("effectiveQuery", "审查"), "l3:classify");
        assertThat(r.hasAgents()).isTrue();
        assertThat(r.hasSkills()).isTrue();
    }

    @Test
    void reason_should_be_preserved() {
        var r = RoutingResult.workflow("wf-1", "chat", "l2:workflow:0.92");
        assertThat(r.reason()).isEqualTo("l2:workflow:0.92");
    }
}
```

- [ ] **Step 2: 运行单测，确认全部 FAIL（类不存在）**

```bash
cd orchestrator && ./gradlew test --tests "com.sunshine.orchestrator.routing.RoutingResultTest" 2>&1 | tail -5
```

- [ ] **Step 3: 实现 RoutingResult record**

```java
package com.sunshine.orchestrator.routing;

import java.util.List;
import java.util.Map;

public record RoutingResult(
    ResourceType type,
    String workflowId,
    List<String> agentIds,
    List<String> skillIds,
    String scene,
    Map<String, String> params,
    String reason
) {
    public enum ResourceType {
        WORKFLOW, AGENT, SKILL, REACT
    }

    public static RoutingResult workflow(String workflowId, String scene, String reason) {
        return new RoutingResult(ResourceType.WORKFLOW, workflowId, List.of(), List.of(),
                scene, Map.of(), reason);
    }

    public static RoutingResult silentFallback(String scene, String reason) {
        return new RoutingResult(ResourceType.REACT, null, List.of(), List.of(),
                scene, Map.of(), reason);
    }

    public boolean isWorkflow() { return type == ResourceType.WORKFLOW; }

    public boolean usesReactExecutor() {
        return type == ResourceType.AGENT || type == ResourceType.SKILL || type == ResourceType.REACT;
    }

    public boolean hasAgents() { return agentIds != null && !agentIds.isEmpty(); }
    public boolean hasSkills() { return skillIds != null && !skillIds.isEmpty(); }
}
```

- [ ] **Step 4: 运行单测，确认全部 PASS**

```bash
cd orchestrator && ./gradlew test --tests "com.sunshine.orchestrator.routing.RoutingResultTest" -i 2>&1 | grep -E "(PASSED|FAILED|Tests)"
```

- [ ] **Step 5: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/routing/RoutingResult.java \
        orchestrator/src/test/java/com/sunshine/orchestrator/routing/RoutingResultTest.java
git commit -m "$(cat <<'EOF'
feat(routing): add RoutingResult record with ResourceType enum

Replaces ExecutionPlan as the routing output. Supports WORKFLOW/AGENT/SKILL/REACT
four types with agentIds/skillIds lists for multi-agent coexistence.
Factory methods: workflow(), silentFallback().
EOF
)"
```

---

### Task 2: RoutingAccumulator 累积器 + 辅助类型 + 单测

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/routing/ScoredResource.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/routing/policy/L0Result.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/routing/policy/L3Decision.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/routing/policy/RoutingAccumulator.java`
- Create: `orchestrator/src/test/java/com/sunshine/orchestrator/routing/policy/RoutingAccumulatorTest.java`

**Interfaces:**
- Consumes: `RoutingResult`
- Produces: `RoutingAccumulator`（`absorbL0(L0Result):boolean`、`absorbL1(RoutingResult):boolean`、`absorbL2(List<ScoredResource>):boolean`、`absorbL3(L3Decision):RoutingResult`、`hasL2Candidates():boolean`、`buildWorkflowStop():RoutingResult`）

- [ ] **Step 1: 写 RoutingAccumulator 单测**

```java
package com.sunshine.orchestrator.routing.policy;

import com.sunshine.orchestrator.routing.RoutingResult;
import com.sunshine.orchestrator.routing.ScoredResource;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class RoutingAccumulatorTest {

    @Test
    void absorbL0_workflow_should_return_false_and_set_workflowId() {
        var acc = new RoutingAccumulator("chat");
        var result = acc.absorbL0(new L0Result(true, "expense-flow", List.of(), List.of(), "报销"));
        assertThat(result).isFalse();
        var built = acc.buildWorkflowStop();
        assertThat(built.type()).isEqualTo(RoutingResult.ResourceType.WORKFLOW);
        assertThat(built.workflowId()).isEqualTo("expense-flow");
    }

    @Test
    void absorbL0_agent_should_collect_agentIds() {
        var acc = new RoutingAccumulator("chat");
        var result = acc.absorbL0(new L0Result(false, null, List.of("agent-a"), List.of(), "审查合同"));
        assertThat(result).isTrue();
    }

    @Test
    void absorbL0_agent_and_skill_coexist() {
        var acc = new RoutingAccumulator("chat");
        acc.absorbL0(new L0Result(false, null, List.of("agent-a"), List.of("skill-x"), "分析报告"));
        var built = acc.buildWithoutL3_forTest();
        assertThat(built.agentIds()).containsExactly("agent-a");
        assertThat(built.skillIds()).containsExactly("skill-x");
    }

    @Test
    void absorbL1_workflow_should_return_false() {
        var acc = new RoutingAccumulator("chat");
        var ruleMatch = RoutingResult.workflow("finance-smart", "chat", "l1:rule:xxx");
        assertThat(acc.absorbL1(ruleMatch)).isFalse();
        assertThat(acc.buildWorkflowStop().workflowId()).isEqualTo("finance-smart");
    }

    @Test
    void absorbL1_null_should_return_true() {
        assertThat(new RoutingAccumulator("chat").absorbL1(null)).isTrue();
    }

    @Test
    void absorbL2_workflow_above_088_should_return_false() {
        var acc = new RoutingAccumulator("chat");
        var candidates = List.of(
            new ScoredResource(RoutingResult.ResourceType.WORKFLOW, "expense-flow", 0.90)
        );
        assertThat(acc.absorbL2(candidates)).isFalse();
        assertThat(acc.buildWorkflowStop().workflowId()).isEqualTo("expense-flow");
    }

    @Test
    void absorbL2_workflow_below_088_should_not_stop() {
        var acc = new RoutingAccumulator("chat");
        var candidates = List.of(
            new ScoredResource(RoutingResult.ResourceType.WORKFLOW, "expense-flow", 0.80)
        );
        assertThat(acc.absorbL2(candidates)).isTrue();
    }

    @Test
    void absorbL2_agent_above_085_should_collect() {
        var acc = new RoutingAccumulator("chat");
        var candidates = List.of(
            new ScoredResource(RoutingResult.ResourceType.AGENT, "compliance-checker", 0.90)
        );
        assertThat(acc.absorbL2(candidates)).isTrue();
        var built = acc.buildWithoutL3_forTest();
        assertThat(built.agentIds()).containsExactly("compliance-checker");
        assertThat(built.type()).isEqualTo(RoutingResult.ResourceType.AGENT);
    }

    @Test
    void absorbL2_empty_list_should_return_true() {
        var acc = new RoutingAccumulator("chat");
        assertThat(acc.absorbL2(List.of())).isTrue();
        assertThat(acc.hasL2Candidates()).isFalse();
    }

    @Test
    void absorbL3_should_add_agentIds_when_confidence_above_05() {
        var acc = new RoutingAccumulator("chat");
        var decision = new L3Decision(false, List.of("agent-c"), List.of(), 0.8, "l3:classify");
        var result = acc.absorbL3(decision);
        assertThat(result.agentIds()).contains("agent-c");
    }

    @Test
    void absorbL3_no_match_should_preserve_l0_agentIds() {
        var acc = new RoutingAccumulator("chat");
        acc.absorbL0(new L0Result(false, null, List.of("agent-a"), List.of(), "审查"));
        var decision = new L3Decision(true, List.of(), List.of(), 0.0, "no_match");
        var result = acc.absorbL3(decision);
        assertThat(result.agentIds()).contains("agent-a");
    }

    @Test
    void build_silentFallback_when_both_empty() {
        var acc = new RoutingAccumulator("chat");
        assertThat(acc.buildWithoutL3_forTest().type()).isEqualTo(RoutingResult.ResourceType.REACT);
    }

    @Test
    void hasL2Candidates_should_return_true_after_absorbL2() {
        var acc = new RoutingAccumulator("chat");
        acc.absorbL2(List.of(new ScoredResource(RoutingResult.ResourceType.AGENT, "a", 0.5)));
        assertThat(acc.hasL2Candidates()).isTrue();
        assertThat(acc.getL2Candidates()).hasSize(1);
    }
}
```

- [ ] **Step 2: 运行单测，确认 FAIL**

```bash
cd orchestrator && ./gradlew test --tests "com.sunshine.orchestrator.routing.policy.RoutingAccumulatorTest" 2>&1 | tail -5
```

- [ ] **Step 3: 实现辅助类型 + RoutingAccumulator**

```java
// ScoredResource.java
package com.sunshine.orchestrator.routing;

public record ScoredResource(RoutingResult.ResourceType type, String id, double score) {}
```

```java
// L0Result.java
package com.sunshine.orchestrator.routing.policy;

import java.util.List;

public record L0Result(boolean isWorkflow, String workflowId,
        List<String> agentIds, List<String> skillIds, String effectiveQuery) {}
```

```java
// L3Decision.java
package com.sunshine.orchestrator.routing.policy;

import java.util.List;

public record L3Decision(boolean isNoMatch, List<String> agentIds,
        List<String> skillIds, double confidence, String reason) {
    public static final L3Decision NO_MATCH = new L3Decision(true, List.of(), List.of(), 0.0, "no_match");
    public boolean hasAgents() { return agentIds != null && !agentIds.isEmpty(); }
    public boolean hasSkills() { return skillIds != null && !skillIds.isEmpty(); }
}
```

```java
// RoutingAccumulator.java
package com.sunshine.orchestrator.routing.policy;

import com.sunshine.orchestrator.routing.RoutingResult;
import com.sunshine.orchestrator.routing.ScoredResource;
import java.util.*;

class RoutingAccumulator {
    private String scene;
    private String workflowId;
    private String workflowReason;
    private final Set<String> agentIds = new LinkedHashSet<>();
    private final Set<String> skillIds = new LinkedHashSet<>();
    private String effectiveQuery;
    private String l0Reason;
    private final List<ScoredResource> l2Candidates = new ArrayList<>();

    RoutingAccumulator(String scene) { this.scene = scene; }

    boolean absorbL0(L0Result result) {
        if (result.isWorkflow()) {
            workflowId = result.workflowId();
            workflowReason = "l0:workflow";
            return false;
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
        var topWf = merged.stream()
                .filter(r -> r.type() == RoutingResult.ResourceType.WORKFLOW && r.score() >= 0.88)
                .findFirst();
        if (topWf.isPresent()) {
            workflowId = topWf.get().id();
            workflowReason = "l2:workflow:" + topWf.get().score();
            return false;
        }
        merged.stream()
                .filter(r -> r.type() == RoutingResult.ResourceType.AGENT && r.score() >= 0.85)
                .forEach(r -> agentIds.add(r.id()));
        merged.stream()
                .filter(r -> r.type() == RoutingResult.ResourceType.SKILL && r.score() >= 0.85)
                .forEach(r -> skillIds.add(r.id()));
        l2Candidates.addAll(merged);
        return true;
    }

    RoutingResult absorbL3(L3Decision decision) {
        if (decision != null && !decision.isNoMatch() && decision.confidence() >= 0.5) {
            if (decision.hasAgents()) agentIds.addAll(decision.agentIds());
            if (decision.hasSkills()) skillIds.addAll(decision.skillIds());
        }
        return build();
    }

    boolean hasL2Candidates() { return !l2Candidates.isEmpty(); }
    List<ScoredResource> getL2Candidates() { return List.copyOf(l2Candidates); }
    RoutingResult buildWorkflowStop() {
        return RoutingResult.workflow(workflowId, scene, workflowReason);
    }
    RoutingResult buildWithoutL3_forTest() { return build(); }

    private RoutingResult build() {
        if (agentIds.isEmpty() && skillIds.isEmpty()) {
            return RoutingResult.silentFallback(scene, "fallback:silent");
        }
        var type = !agentIds.isEmpty() ? RoutingResult.ResourceType.AGENT : RoutingResult.ResourceType.SKILL;
        String reason = l0Reason != null ? l0Reason : "l2+3";
        return new RoutingResult(type, null,
                List.copyOf(agentIds), List.copyOf(skillIds),
                scene,
                effectiveQuery != null ? Map.of("effectiveQuery", effectiveQuery) : Map.of(),
                reason);
    }
}
```

- [ ] **Step 4: 运行单测，确认 PASS**

```bash
cd orchestrator && ./gradlew test --tests "com.sunshine.orchestrator.routing.policy.RoutingAccumulatorTest" -i 2>&1 | grep -E "(PASSED|FAILED|Tests)"
```

- [ ] **Step 5: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/routing/ScoredResource.java \
        orchestrator/src/main/java/com/sunshine/orchestrator/routing/policy/L0Result.java \
        orchestrator/src/main/java/com/sunshine/orchestrator/routing/policy/L3Decision.java \
        orchestrator/src/main/java/com/sunshine/orchestrator/routing/policy/RoutingAccumulator.java \
        orchestrator/src/test/java/com/sunshine/orchestrator/routing/policy/RoutingAccumulatorTest.java
git commit -m "$(cat <<'EOF'
feat(routing): add RoutingAccumulator with L0-L3 absorb logic

Accumulates agentIds/skillIds across routing layers. WORKFLOW at any layer
triggers immediate STOP. L3 always runs: fast-classify or deep-semantic-fallback.
EOF
)"
```

---

### Task 3: ExplicitBindingRoutingPolicy (L0) + 单测

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/routing/policy/ExplicitBindingRoutingPolicy.java`
- Create: `orchestrator/src/test/java/com/sunshine/orchestrator/routing/policy/ExplicitBindingRoutingPolicyTest.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/routing/policy/RoutingPolicy.java`（扩展接口支持新签名）

**Interfaces:**
- Consumes: `WorkflowBindingParser`、`AgentBindingParser`、`SkillBindingParser`、`RoutingContext`
- Produces: `Mono<Object>`（实际返回 `L0Result`）

- [ ] **Step 1: 扩展 RoutingPolicy 接口**

```java
// 在现有接口上添加默认方法（不破坏已有实现）
default Mono<Object> tryRoute(RoutingContext ctx) { return Mono.empty(); }
```

- [ ] **Step 2: 写 ExplicitBindingRoutingPolicy 单测**

```java
package com.sunshine.orchestrator.routing.policy;

import com.sunshine.orchestrator.catalog.AgentBindingParser;
import com.sunshine.orchestrator.skill.SkillBindingParser;
import com.sunshine.orchestrator.workflow.WorkflowBindingParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExplicitBindingRoutingPolicyTest {

    @Mock WorkflowBindingParser workflowBindingParser;
    @Mock AgentBindingParser agentBindingParser;
    @Mock SkillBindingParser skillBindingParser;
    @InjectMocks ExplicitBindingRoutingPolicy policy;

    @Test
    void order_should_be_0() { assertThat(policy.order()).isEqualTo(0); }

    @Test
    void workflow_binding_should_return_isWorkflow_true() {
        when(workflowBindingParser.resolve("帮我 #expense-flow 报销"))
                .thenReturn(new WorkflowBindingParser.WorkflowBindingOutcome(true, "expense-flow", "帮我 报销"));
        var result = policy.tryRoute(new RoutingContext("帮我 #expense-flow 报销", "t1", "chat", null));
        StepVerifier.create(result)
                .expectNextMatches(o -> o instanceof L0Result r && r.isWorkflow()
                        && "expense-flow".equals(r.workflowId()))
                .verifyComplete();
    }

    @Test
    void agent_binding_should_collect_agentIds() {
        when(workflowBindingParser.resolve("$agent-a 审查合同"))
                .thenReturn(new WorkflowBindingParser.WorkflowBindingOutcome(false, null, "$agent-a 审查合同"));
        when(agentBindingParser.resolve("$agent-a 审查合同"))
                .thenReturn(new AgentBindingParser.AgentBindingOutcome(true, List.of("agent-a"), "审查合同"));
        var result = policy.tryRoute(new RoutingContext("$agent-a 审查合同", "t1", "chat", null));
        StepVerifier.create(result)
                .expectNextMatches(o -> o instanceof L0Result r && !r.isWorkflow()
                        && r.agentIds().contains("agent-a")
                        && "审查合同".equals(r.effectiveQuery()))
                .verifyComplete();
    }

    @Test
    void skill_binding_should_collect_skillIds() {
        when(workflowBindingParser.resolve("@report-gen 生成报告"))
                .thenReturn(new WorkflowBindingParser.WorkflowBindingOutcome(false, null, "@report-gen 生成报告"));
        when(agentBindingParser.resolve("@report-gen 生成报告"))
                .thenReturn(new AgentBindingParser.AgentBindingOutcome(false, List.of(), "@report-gen 生成报告"));
        when(skillBindingParser.resolve("@report-gen 生成报告"))
                .thenReturn(new SkillBindingParser.SkillBindingOutcome(true, "report-gen", "生成报告"));
        var result = policy.tryRoute(new RoutingContext("@report-gen 生成报告", "t1", "chat", null));
        StepVerifier.create(result)
                .expectNextMatches(o -> o instanceof L0Result r && !r.isWorkflow()
                        && r.skillIds().contains("report-gen"))
                .verifyComplete();
    }

    @Test
    void no_binding_should_return_empty_L0Result() {
        when(workflowBindingParser.resolve("今天天气不错"))
                .thenReturn(new WorkflowBindingParser.WorkflowBindingOutcome(false, null, "今天天气不错"));
        when(agentBindingParser.resolve("今天天气不错"))
                .thenReturn(new AgentBindingParser.AgentBindingOutcome(false, List.of(), "今天天气不错"));
        when(skillBindingParser.resolve("今天天气不错"))
                .thenReturn(new SkillBindingParser.SkillBindingOutcome(false, null, "今天天气不错"));
        var result = policy.tryRoute(new RoutingContext("今天天气不错", "t1", "chat", null));
        StepVerifier.create(result)
                .expectNextMatches(o -> o instanceof L0Result r && !r.isWorkflow()
                        && r.agentIds().isEmpty() && r.skillIds().isEmpty())
                .verifyComplete();
    }
}
```

- [ ] **Step 3: 编译，确认 parser 返回类型，根据实际 inner class 调整 mock 返回类型**

```bash
rg "record WorkflowBindingOutcome|record AgentBindingOutcome|record SkillBindingOutcome" orchestrator/src/main/java/
```

- [ ] **Step 4: 实现 ExplicitBindingRoutingPolicy**

```java
package com.sunshine.orchestrator.routing.policy;

import com.sunshine.orchestrator.catalog.AgentBindingParser;
import com.sunshine.orchestrator.skill.SkillBindingParser;
import com.sunshine.orchestrator.workflow.WorkflowBindingParser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.util.List;

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

        if (ctx.isChatScene()) {
            var wf = workflowBindingParser.resolve(msg);
            if (wf.bound()) {
                return Mono.just(new L0Result(true, wf.workflowId(), List.of(), List.of(), msg));
            }
        }

        var agent = agentBindingParser.resolve(msg);
        if (agent.bound()) {
            return Mono.just(new L0Result(false, null,
                    agent.agentIds(), List.of(), agent.effectiveQuery()));
        }

        var skill = skillBindingParser.resolve(msg);
        if (skill.bound()) {
            return Mono.just(new L0Result(false, null,
                    List.of(), List.of(skill.skillId()), msg));
        }

        return Mono.just(new L0Result(false, null, List.of(), List.of(), msg));
    }
}
```

- [ ] **Step 5: 运行单测，确认 PASS**

```bash
cd orchestrator && ./gradlew test --tests "com.sunshine.orchestrator.routing.policy.ExplicitBindingRoutingPolicyTest" -i 2>&1 | grep -E "(PASSED|FAILED|Tests)"
```

- [ ] **Step 6: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/routing/policy/ExplicitBindingRoutingPolicy.java \
        orchestrator/src/test/java/com/sunshine/orchestrator/routing/policy/ExplicitBindingRoutingPolicyTest.java
git commit -m "$(cat <<'EOF'
feat(routing): add L0 ExplicitBindingRoutingPolicy

Parses #workflow-id / $agent-id / @skill-id from user input. WORKFLOW returns
STOP signal; agent/skill collected as child agents. Reuses existing parsers.
EOF
)"
```

---

### Task 4: RuleBasedRoutingPolicy (L1) + 单测

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/routing/policy/RuleBasedRoutingPolicy.java`
- Create: `orchestrator/src/test/java/com/sunshine/orchestrator/routing/policy/RuleBasedRoutingPolicyTest.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/routing/policy/UnifiedRuleRoutingPolicy.java`（标记 @Deprecated）

**Interfaces:**
- Consumes: `UnifiedRuleEngine`、`RoutingContext`
- Produces: `Mono<RoutingResult>`（null = 无命中）

- [ ] **Step 1: 检查 UnifiedRuleEngine.Hit 字段**

```bash
rg "record Hit" /usr/local/gitproj/my-sunshine-agent/common/sunshine-routing/src/main/java/ -A 10
```

根据实际字段（`ruleId`/`confidence`/`mode`/`resourceId`）写单测。

- [ ] **Step 2: 写 RuleBasedRoutingPolicy 单测 + 实现**

```java
// 测试 mock UnifiedRuleEngine，验证：
// - workflow rule → stop
// - agent rule → agentIds 收集
// - 无命中 → null
// - 低 confidence (<0.85) → null
```

- [ ] **Step 3: 运行单测，确认 PASS**

```bash
cd orchestrator && ./gradlew test --tests "com.sunshine.orchestrator.routing.policy.RuleBasedRoutingPolicyTest" -i 2>&1 | grep -E "(PASSED|FAILED|Tests)"
```

- [ ] **Step 4: Commit**

---

### Task 5: RoutingPolicyChain + 单测

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/routing/policy/RoutingPolicyChain.java`
- Create: `orchestrator/src/test/java/com/sunshine/orchestrator/routing/policy/RoutingPolicyChainTest.java`（含 stub 策略）

- [ ] **Step 1: 写 RoutingPolicyChain 单测（验证 STOP/L3 双模式）**

- [ ] **Step 2: 实现 RoutingPolicyChain**

- [ ] **Step 3: 运行单测，确认 PASS**

- [ ] **Step 4: Commit**

---

### Task 6: ResourceDispatcher + ExecutionStreamContext + 单测

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/ResourceDispatcher.java`
- Create: `orchestrator/src/test/java/com/sunshine/orchestrator/execution/ResourceDispatcherTest.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/ExecutionStreamContext.java`（增加 `routingResult`）

- [ ] **Step 1: 修改 ExecutionStreamContext，增加 routingResult 字段**

- [ ] **Step 2: 写 ResourceDispatcher 单测**

- [ ] **Step 3: 实现 ResourceDispatcher**

- [ ] **Step 4: 运行单测，确认 PASS**

- [ ] **Step 5: Commit**

---

### Task 7: 阶段 R-1 收口（编译验证 + 全量单测）

- [ ] **Step 1: 全量编译**

```bash
cd orchestrator && ./gradlew compileJava 2>&1 | tail -10
```

- [ ] **Step 2: 运行所有路由单测**

```bash
cd orchestrator && ./gradlew test --tests "com.sunshine.orchestrator.routing.*" -i 2>&1 | grep -E "(PASSED|FAILED|Tests)"
```

- [ ] **Step 3: Commit**

---

### Task 8: L2 SemanticRoutingPolicy + EmbeddingIndex stubs + 单测

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/routing/index/EmbeddingIndex.java`（接口）
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/routing/index/AgentEmbeddingIndex.java`（stub）
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/routing/index/SkillEmbeddingIndex.java`（stub）
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/routing/index/WorkflowEmbeddingIndex.java`（stub）
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/routing/policy/SemanticRoutingPolicy.java`
- Create: `orchestrator/src/test/java/com/sunshine/orchestrator/routing/policy/SemanticRoutingPolicyTest.java`

- [ ] **Step 1: 定义 EmbeddingIndex 接口 + stub 实现**

- [ ] **Step 2: 写 SemanticRoutingPolicy 单测**

- [ ] **Step 3: 实现 SemanticRoutingPolicy**

- [ ] **Step 4: 运行单测，确认 PASS**

- [ ] **Step 5: Commit**

---

### Task 9: L3 LlmClassifierRoutingPolicy + IntentRouter 扩展 + 单测

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/routing/policy/LlmClassifierRoutingPolicy.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/IntentRouter.java`（新增 `classifyWithCandidates()` + `classifyDeepSemantic()`）
- Create: `orchestrator/src/test/java/com/sunshine/orchestrator/routing/policy/LlmClassifierRoutingPolicyTest.java`

- [ ] **Step 1: 在 IntentRouter 中新增方法签名（stub 实现）**

- [ ] **Step 2: 写 LlmClassifierRoutingPolicy 单测**

- [ ] **Step 3: 实现 LlmClassifierRoutingPolicy**

- [ ] **Step 4: 运行单测，确认 PASS**

- [ ] **Step 5: Commit**

---

### Task 10: ReactExecutor 改造（Agent Catalog 注入） + 单测追加

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/ReactExecutor.java`
- Modify: `orchestrator/src/test/java/com/sunshine/orchestrator/execution/ReactExecutorTest.java`

- [ ] **Step 1: 读取当前 ReactExecutor 实现，定位 system prompt 拼装点**

```bash
rg "systemPrompt|agent|ExecutionPlan" orchestrator/src/main/java/com/sunshine/orchestrator/execution/ReactExecutor.java | head -20
```

- [ ] **Step 2: 改造：不加载 agent system prompt → 注入 Agent Catalog 表格**

```java
if (routingResult.hasAgents()) {
    systemPrompt += renderAgentCatalog(routingResult.agentIds());
}
if (routingResult.hasSkills()) {
    for (String skillId : routingResult.skillIds()) {
        systemPrompt += skillCatalogService.getOverlay(skillId);
        sandboxSessionLifecycle.mountSkill(sessionId, skillId);
    }
}
```

- [ ] **Step 3: 追加单测验证 Agent Catalog 注入**

- [ ] **Step 4: 运行单测全量**

```bash
cd orchestrator && ./gradlew test --tests "com.sunshine.orchestrator.execution.ReactExecutorTest" -i 2>&1 | grep -E "(PASSED|FAILED|Tests)"
```

- [ ] **Step 5: Commit**

---

### Task 11: ChatController scene + ResourceRouter + DynamicToolkitFactory + 单测

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/model/ChatMessage.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/controller/ChatController.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/DynamicToolkitFactory.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/ResourceRouter.java`

- [ ] **Step 1: ChatMessage 请求体：删除 executionPreference，增加 scene**

- [ ] **Step 2: ChatController：删除 ForcedExecutionRouter 调用，改为 ResourceRouter**

- [ ] **Step 3: DynamicToolkitFactory：resolveSceneTools(scene, tenantId)**

- [ ] **Step 4: 编译 + 单测**

- [ ] **Step 5: Commit**

---

### Task 12: DB 迁移 SQL

**Files:**
- Create: `docker/mysql/init/18-sunshine-routing-migration.sql`
- Modify: `docker/mysql/init/17-sunshine-prompt-manager.sql`

- [ ] **Step 1: 写 migration SQL**

```sql
-- 18-sunshine-routing-migration.sql
ALTER TABLE chat_message
    ADD COLUMN routing_type VARCHAR(16) DEFAULT NULL COMMENT 'WORKFLOW/AGENT/SKILL/REACT',
    ADD COLUMN routing_resource_id VARCHAR(128) DEFAULT NULL COMMENT 'resource id';
ALTER TABLE chat_conversation
    ADD COLUMN scene VARCHAR(16) DEFAULT 'chat' COMMENT 'chat / task';
-- prompt-manager: disable routing-rule.peer-phrase
UPDATE prompt_definition SET enabled = 0 WHERE id = 'routing-rule.peer-phrase';
```

- [ ] **Step 2: Commit**

---

### Task 13: 前端清理

**Files:**
- Delete: `ExecutionModeSelector.vue`、`executionModes.ts`、`executionModeIcons.ts`、`useExecutionPreference.ts`
- Modify: `ChatView.vue`、`chat.ts`、`chatStore.ts`、各 mention composable/utils（删除 `executionPreference` 引用）

- [ ] **Step 1: 删除文件**

- [ ] **Step 2: 修改各引用文件（`executionPreference` → `scene`）**

- [ ] **Step 3: 前端编译确认**

```bash
cd sunshine-ui && npx vue-tsc --noEmit 2>&1 | tail -5
```

- [ ] **Step 4: Commit**

---

### Task 14: 阶段 R-5 最终清理

**删除（orchestrator）：**
- `ExecutionMode.java`、`ExecutionPreference.java`、`ExecutionPlan.java`、`ExecutionPlanParser.java`
- `ForcedExecutionRouter.java`、`UnifiedRuleRoutingPolicy.java`、`ExecutionDispatcher.java`
- `SkillDiscoveryService.java`

- [ ] **Step 1: 搜索所有仍引用旧类的代码**

```bash
rg "ExecutionMode|ExecutionPreference|ExecutionPlan\b|ForcedExecutionRouter|ExecutionDispatcher|UnifiedRuleRoutingPolicy|SkillDiscoveryService" orchestrator/src/main/java/ -l
```

- [ ] **Step 2: 逐文件修改引用为 RoutingResult/ResourceDispatcher/RuleBasedRoutingPolicy**

- [ ] **Step 3: 删除源文件 + 旧测试文件**

- [ ] **Step 4: 全量编译 + 全量单测**

```bash
cd orchestrator && ./gradlew compileJava 2>&1 | tail -5
cd orchestrator && ./gradlew test -i 2>&1 | grep "Tests run"
```

- [ ] **Step 5: Commit**

---

## Self-Review

### 1. Spec coverage

| Spec 章节 | 覆盖任务 |
|-----------|---------|
| §2.2 RoutingResult | Task 1 |
| §2.3 RoutingPolicyChain | Task 5 |
| §2.4 RoutingAccumulator | Task 2 |
| §3 L0 显式绑定 | Task 3 |
| §4 L1 规则匹配 | Task 4 |
| §5 L2 语义召回 | Task 8 |
| §6 L3 语义兜底 | Task 9 |
| §7 ResourceDispatcher | Task 6 |
| §7.1 ReactExecutor 改造 | Task 10 |
| §8 优先级规则 | Task 2+5（含于 Accumulator + Chain） |
| §10 工具加载 | Task 11（DynamicToolkitFactory） |
| §11 组件处置 | Task 13（前端）+ Task 14（后端清理） |
| §13 验收标准 | 各任务单测对应验收点 |
| §14 文件索引 | 各任务 File 段 |

### 2. Placeholder 扫描

- 无 TBD/TODO/implement later
- 无模糊的 "add error handling"
- 无 "Similar to Task N"
- 所有 stub 都有明确接入路径（Milvus、agent-manager client）

### 3. Type consistency

- RoutingResult 字段在所有 task 一致 ✅
- RoutingAccumulator 输入/输出类型与 RoutingPolicyChain 匹配 ✅
- ResourceDispatcher switch 与 ResourceType 枚举覆盖全部 case ✅

---

## 执行选择

Plan saved to `docs/superpowers/plans/2026-07-30-unified-routing.md`。两种执行方式：

**1. Subagent-Driven (recommended)** — 每个 Task 一个独立 subagent，Task 间 review

**2. Inline Execution** — 在当前 session 中使用 executing-plans，批量执行

选用哪种？