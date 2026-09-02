package com.sunshine.routing;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnifiedRuleEngineTest {
    @Test
    void structuralBeatsLowerPriorityRegex() {
        RoutingRuleDef structural = rule("structural-plan", 100, "structural",
                List.of("先.+再"), Map.of("knowledge", List.of("制度"), "finance", List.of("报销"), "analysis", List.of("分析")),
                "pro", null);
        RoutingRuleDef regex = rule("finance-list", 10, "regex",
                List.of("待审批"), Map.of(), "workflow", "finance-list");
        UnifiedRuleEngine engine = new UnifiedRuleEngine(List.of(regex, structural));
        Optional<UnifiedRuleEngine.Hit> hit = engine.match("先检索制度再分析报销待审批");
        assertTrue(hit.isPresent());
        assertEquals("structural-plan", hit.get().ruleId());
        assertEquals("pro", hit.get().plan().mode());
    }

    @Test
    void regexFirstByPriority() {
        RoutingRuleDef a = rule("a", 20, "regex", List.of("是否合规"), Map.of(), "workflow", "finance-smart");
        RoutingRuleDef b = rule("b", 10, "regex", List.of("是否合规"), Map.of(), "workflow", "other");
        Optional<UnifiedRuleEngine.Hit> hit = new UnifiedRuleEngine(List.of(b, a)).match("这样是否合规");
        assertEquals("a", hit.get().ruleId());
    }

    @Test
    void dryRunWouldLlmWhenNoMatch() {
        RoutingRuleDef regex = rule("a", 10, "regex", List.of("待审批"), Map.of(), "workflow", "finance-list");
        RoutingDryRunResult result = RoutingDryRunResult.dryRun("随便聊聊", List.of(regex));
        assertNull(result.matchedRuleId());
        assertTrue(result.wouldLlm());
        assertEquals("would_llm", result.stage());
    }

    @Test
    void dryRunRuleEngineWhenMatched() {
        RoutingRuleDef regex = rule("a", 10, "regex", List.of("待审批"), Map.of(), "workflow", "finance-list");
        RoutingDryRunResult result = RoutingDryRunResult.dryRun("查询待审批", List.of(regex));
        assertEquals("a", result.matchedRuleId());
        assertFalse(result.wouldLlm());
        assertEquals("rule-engine", result.stage());
    }

    @Test
    void disabledRulesSkipped() {
        RoutingRuleDef disabled = new RoutingRuleDef("disabled", 100, false, "regex", "any",
                List.of("命中"), Map.of(), 2, new RoutingPlanSpec("workflow", "x", Map.of()));
        RoutingRuleDef enabled = rule("enabled", 10, "regex", List.of("命中"), Map.of(), "workflow", "y");
        Optional<UnifiedRuleEngine.Hit> hit = new UnifiedRuleEngine(List.of(disabled, enabled)).match("这里命中");
        assertTrue(hit.isPresent());
        assertEquals("enabled", hit.get().ruleId());
    }

    private static RoutingRuleDef rule(String id, int priority, String matchType,
                                       List<String> patterns, Map<String, List<String>> groups,
                                       String mode, String workflowId) {
        return new RoutingRuleDef(id, priority, true, matchType, "any", patterns, groups, 2,
                new RoutingPlanSpec(mode, workflowId, Map.of()));
    }
}
