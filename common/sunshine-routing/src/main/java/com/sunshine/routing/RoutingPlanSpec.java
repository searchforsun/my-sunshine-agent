package com.sunshine.routing;

import java.util.Map;

public record RoutingPlanSpec(String mode, String workflowId, Map<String, String> params) {}
