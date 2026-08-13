package com.sunshine.prompt.dto;

public record RoutingDryRunRequest(String query, Boolean includeL0Hints, String mode) {
    public RoutingDryRunRequest(String query, Boolean includeL0Hints) {
        this(query, includeL0Hints, null);
    }
}
