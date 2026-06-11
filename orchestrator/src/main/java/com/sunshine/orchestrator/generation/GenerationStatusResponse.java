package com.sunshine.orchestrator.generation;

public record GenerationStatusResponse(
        String status,
        long lastSeq,
        String messageId,
        String conversationId,
        String generationId
) {
    public static GenerationStatusResponse from(GenerationMeta meta) {
        return new GenerationStatusResponse(
                meta.status().name(),
                meta.lastSeq(),
                meta.messageId(),
                meta.conversationId(),
                meta.generationId()
        );
    }
}
