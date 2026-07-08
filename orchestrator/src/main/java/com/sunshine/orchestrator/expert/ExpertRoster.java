package com.sunshine.orchestrator.expert;

import java.util.List;

public record ExpertRoster(List<String> expertIds, String reason, Integer sessionMaxRounds) {
    public ExpertRoster(List<String> expertIds, String reason) {
        this(expertIds, reason, null);
    }
}
