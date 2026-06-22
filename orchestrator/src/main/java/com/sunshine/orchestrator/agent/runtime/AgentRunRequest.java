package com.sunshine.orchestrator.agent.runtime;

import com.sunshine.orchestrator.memory.MemoryContext;

import java.util.List;
import java.util.UUID;

/** 主/子 Agent 统一执行请求 */
public record AgentRunRequest(
        AgentRole role,
        String runId,
        String parentRunId,
        MemoryContext memory,
        String query,
        List<String> injectedBlocks,
        String userId,
        String tenantId,
        String assistantMessageId,
        String skillId,
        List<String> toolWhitelist,
        String systemOverlay,
        int maxIters,
        TimelineBinding timeline
) {
    public AgentRunRequest {
        memory = memory != null ? memory : MemoryContext.empty();
        injectedBlocks = injectedBlocks != null ? List.copyOf(injectedBlocks) : List.of();
    }

    /** 顶层 ReAct — 绑定 assistantMessageId，全量 Timeline */
    public static AgentRunRequest main(
            MemoryContext memory,
            String query,
            String userId,
            String tenantId,
            String assistantMessageId,
            List<String> injectedBlocks) {
        return new AgentRunRequest(
                AgentRole.MAIN,
                UUID.randomUUID().toString(),
                null,
                memory,
                query,
                injectedBlocks,
                userId,
                tenantId,
                assistantMessageId,
                null,
                null,
                null,
                0,
                TimelineBinding.MAIN_FULL);
    }

    public static AgentRunRequest main(
            MemoryContext memory, String query, String userId, String tenantId, String assistantMessageId) {
        return main(memory, query, userId, tenantId, assistantMessageId, List.of());
    }

    /** Workflow 子 Agent — 不绑定 assistantMessageId，压缩 Timeline */
    public static AgentRunRequest sub(
            MemoryContext memory,
            String query,
            List<String> injectedBlocks,
            String userId,
            String tenantId) {
        return new AgentRunRequest(
                AgentRole.SUB,
                UUID.randomUUID().toString(),
                null,
                memory,
                query,
                injectedBlocks,
                userId,
                tenantId,
                null,
                null,
                null,
                null,
                0,
                TimelineBinding.SUB_COMPRESSED);
    }
}
