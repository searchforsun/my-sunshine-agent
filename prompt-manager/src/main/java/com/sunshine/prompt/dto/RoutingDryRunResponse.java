package com.sunshine.prompt.dto;

import com.sunshine.routing.RoutingPlanSpec;

public record RoutingDryRunResponse(String matchedRuleId, boolean wouldLlm, String stage, RoutingPlanSpec plan) {}
