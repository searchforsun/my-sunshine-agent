package com.sunshine.orchestrator.controller.stream;

import com.sunshine.orchestrator.memory.MemoryContext;
import com.sunshine.orchestrator.routing.ExecutionPreference;

/** 单次 chat/stream 请求的会话与执行上下文（新消息或续跑） */
public record ChatStreamContext(
        String conversationId,
        String assistantMsgId,
        String conversationTitle,
        String userContent,
        MemoryContext memory,
        String existingContent,
        String existingReasoning,
        String intent,
        String existingStepsJson,
        boolean autoTitle,
        String userId,
        String tenantId,
        ExecutionPreference executionPreference,
        String forcedWorkflowId,
        String clientSkillId,
        boolean reactRestart) {
}
