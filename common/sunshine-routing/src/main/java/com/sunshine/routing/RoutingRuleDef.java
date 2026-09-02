package com.sunshine.routing;

import java.util.List;
import java.util.Map;

public record RoutingRuleDef(
        String id,
        int priority,
        boolean enabled,
        String matchType,
        String match,
        List<String> patterns,
        Map<String, List<String>> domainGroups,
        int minDomainGroups,
        RoutingPlanSpec plan) {}
