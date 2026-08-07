package com.sunshine.orchestrator.controller.stream;

import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.routing.ExecutionPreference;

/** 续跑前从 DB 组装的上下文 */
public record ChatResumePreparation(
        String assistantId,
        String conversationId,
        String userContent,
        AssembledContext memory,
        String resumeContent,
        String resumeReasoning,
        String intent,
        String stepsJson,
        String contentBlocksJson,
        boolean reactRestart,
        String userId,
        String tenantId,
        String kbId,
        String conversationKind) {

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
                null,
                kbId,
                reactRestart,
                // 续跑不重注入个人规则（原始 run 已注入；规则随新消息生效）
                null,
                conversationKind);
    }
}
