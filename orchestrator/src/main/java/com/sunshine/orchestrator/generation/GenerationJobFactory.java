package com.sunshine.orchestrator.generation;

import com.sunshine.orchestrator.conversation.GenerationFlushScheduler;
import com.sunshine.orchestrator.memory.MemoryLifecycleService;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class GenerationJobFactory {

    private final GenerationStreamService streamService;
    private final GenerationProperties properties;
    private final GenerationFlushScheduler flushScheduler;
    private final MemoryLifecycleService memoryLifecycleService;

    public GenerationJob create(String generationId, String messageId, String conversationId,
            String userId, String tenantId, String intent, String userQuery) {
        return new GenerationJob(
                generationId, messageId, conversationId, userId, tenantId, intent, userQuery,
                streamService, properties, flushScheduler, memoryLifecycleService);
    }
}
