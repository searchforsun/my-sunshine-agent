package com.sunshine.orchestrator.processing;

public record ProcessingEvent(
        String stepId,
        String phase,
        EventKind kind,
        String summary,
        long ts,
        String detail,
        StepMetadata metadata
) {
}
