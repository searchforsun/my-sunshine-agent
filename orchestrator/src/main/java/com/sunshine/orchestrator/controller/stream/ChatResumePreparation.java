package com.sunshine.orchestrator.controller.stream;

import com.sunshine.orchestrator.memory.MemoryContext;
import com.sunshine.orchestrator.routing.ExecutionPreference;

/** 续跑前从 DB 组装的上下文 */
public record ChatResumePreparation(
        String assistantId,
        String conversationId,
        String userContent,
        MemoryContext memory,
        String resumeContent,
        String resumeReasoning,
        String intent,
        String stepsJson,
        String userId,
        String tenantId) {

    public ChatStreamContext toStreamContext() {
        return new ChatStreamContext(
                conversationId,
                assistantId,
                null,
                userContent,
                memory,
                resumeContent,
                resumeReasoning,
                intent,
                stepsJson,
                false,
                userId,
                tenantId,
                ExecutionPreference.AUTO,
                null,
                null);
    }
}
