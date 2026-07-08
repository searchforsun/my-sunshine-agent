package com.sunshine.orchestrator.expert;

import java.util.List;

public record ExpertBindingOutcome(
        boolean bound,
        boolean unknown,
        List<String> expertIds,
        String effectiveQuery,
        ExpertBindingSource source
) {
    public static ExpertBindingOutcome none(String query) {
        return new ExpertBindingOutcome(false, false, List.of(), query != null ? query : "", null);
    }

    public static ExpertBindingOutcome unknown(String token) {
        return new ExpertBindingOutcome(false, true, List.of(), "", null);
    }

    public static ExpertBindingOutcome bound(List<String> expertIds, String effectiveQuery) {
        return new ExpertBindingOutcome(true, false, List.copyOf(expertIds), effectiveQuery, ExpertBindingSource.DOLLAR_MENTION);
    }
}
