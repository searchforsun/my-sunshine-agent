package com.sunshine.orchestrator.generation;

import com.sunshine.orchestrator.conversation.GenerationFlushScheduler;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class GenerationJobFactory {

    private final GenerationStreamService streamService;
    private final GenerationProperties properties;
    private final GenerationFlushScheduler flushScheduler;

    public GenerationJob create(String generationId, String messageId, String conversationId,
            String userId, String tenantId, String intent) {
        return new GenerationJob(
                generationId, messageId, conversationId, userId, tenantId, intent,
                streamService, properties, flushScheduler);
    }
}
