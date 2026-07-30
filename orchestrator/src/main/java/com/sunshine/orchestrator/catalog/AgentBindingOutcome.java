package com.sunshine.orchestrator.catalog;

import java.util.List;

public record AgentBindingOutcome(
        boolean bound,
        boolean unknown,
        List<String> expertIds,
        String effectiveQuery,
        AgentBindingSource source
) {
    public static AgentBindingOutcome none(String query) {
        return new AgentBindingOutcome(false, false, List.of(), query != null ? query : "", null);
    }

    public static AgentBindingOutcome unknown(String token) {
        return new AgentBindingOutcome(false, true, List.of(), "", null);
    }

    public static AgentBindingOutcome bound(List<String> expertIds, String effectiveQuery) {
        return new AgentBindingOutcome(true, false, List.copyOf(expertIds), effectiveQuery, AgentBindingSource.DOLLAR_MENTION);
    }
}
