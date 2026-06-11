package com.sunshine.orchestrator.generation;

import com.sunshine.orchestrator.conversation.GenerationFlushScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnBean(StringRedisTemplate.class)
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
