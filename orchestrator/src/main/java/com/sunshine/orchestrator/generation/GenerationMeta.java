package com.sunshine.orchestrator.generation;

public record GenerationMeta(
        String generationId,
        String conversationId,
        String messageId,
        String userId,
        String tenantId,
        GenerationStatus status,
        long lastSeq,
        String intent
) {
}
