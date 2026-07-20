package com.sunshine.routing;

import java.util.List;

public record RoutingDryRunResult(String matchedRuleId, boolean wouldLlm, String stage) {

    public static RoutingDryRunResult dryRun(String query, List<RoutingRuleDef> rules) {
        return UnifiedRuleEngine.dryRun(query, rules);
    }
}
