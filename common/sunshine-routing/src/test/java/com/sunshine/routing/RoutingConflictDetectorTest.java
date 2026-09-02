package com.sunshine.routing;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutingConflictDetectorTest {

    @Test
    void samePriorityWarning() {
        RoutingRuleDef a = rule("a", 20, "regex", List.of("foo"));
        RoutingRuleDef b = rule("b", 20, "regex", List.of("bar"));
        List<RoutingConflictDetector.Warning> warnings = RoutingConflictDetector.detect(List.of(a, b));
        assertTrue(warnings.stream().anyMatch(w -> w.message().contains("same priority 20")));
    }

    @Test
    void regexPatternContainmentWarning() {
        RoutingRuleDef a = rule("a", 20, "regex", List.of("是否合规"));
        RoutingRuleDef b = rule("b", 15, "regex", List.of("合规"));
        List<RoutingConflictDetector.Warning> warnings = RoutingConflictDetector.detect(List.of(a, b));
        assertTrue(warnings.stream().anyMatch(w -> w.message().contains("pattern containment")));
    }

    @Test
    void noWarningForDistinctRules() {
        RoutingRuleDef a = rule("a", 20, "regex", List.of("foo"));
        RoutingRuleDef b = rule("b", 10, "regex", List.of("bar"));
        List<RoutingConflictDetector.Warning> warnings = RoutingConflictDetector.detect(List.of(a, b));
        assertFalse(warnings.stream().anyMatch(w -> w.message().contains("same priority")));
        assertFalse(warnings.stream().anyMatch(w -> w.message().contains("pattern containment")));
    }

    private static RoutingRuleDef rule(String id, int priority, String matchType, List<String> patterns) {
        return new RoutingRuleDef(id, priority, true, matchType, "any", patterns, Map.of(), 2,
                new RoutingPlanSpec("workflow", "wf", Map.of()));
    }
}
