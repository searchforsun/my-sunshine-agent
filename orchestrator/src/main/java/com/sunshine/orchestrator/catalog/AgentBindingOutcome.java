package com.sunshine.orchestrator.catalog;

import java.util.List;

public record AgentBindingOutcome(
        boolean bound,
        boolean unknown,
        List<String> agentIds,
        String effectiveQuery,
        AgentBindingSource source
) {
    public static AgentBindingOutcome none(String query) {
        return new AgentBindingOutcome(false, false, List.of(), query != null ? query : "", null);
    }

    public static AgentBindingOutcome unknown(String token) {
        return new AgentBindingOutcome(false, true, List.of(), "", null);
    }

    public static AgentBindingOutcome bound(List<String> agentIds, String effectiveQuery) {
        return new AgentBindingOutcome(true, false, List.copyOf(agentIds), effectiveQuery, AgentBindingSource.DOLLAR_MENTION);
    }
}
