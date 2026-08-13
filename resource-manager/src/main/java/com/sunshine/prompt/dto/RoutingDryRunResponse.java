package com.sunshine.prompt.dto;

import com.sunshine.routing.RoutingPlanSpec;

/** stage：rule=同轨规则命中；l3=将走 L3 补绑定（不改模式） */
public record RoutingDryRunResponse(String matchedRuleId, String stage, RoutingPlanSpec plan) {}
