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
        toolWhitelist = toolWhitelist != null ? List.copyOf(toolWhitelist) : null;
    }

    /** MAIN 绑定 assistantMessageId；SUB 用 runId 生成独立 bridge */
    public String resolveBridgeId() {
        if (assistantMessageId != null && !assistantMessageId.isBlank()) {
            return assistantMessageId;
        }
        return "sub-" + runId;
    }

    /** 顶层 ReAct — 绑定 assistantMessageId，全量 Timeline */
    public static AgentRunRequest main(
            MemoryContext memory,
            String query,
            String userId,
            String tenantId,
            String assistantMessageId,
            List<String> injectedBlocks,
            String skillId) {
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
                skillId,
                null,
                null,
                0,
                TimelineBinding.MAIN_FULL);
    }

    public static AgentRunRequest main(
            MemoryContext memory,
            String query,
            String userId,
            String tenantId,
            String assistantMessageId,
            List<String> injectedBlocks) {
        return main(memory, query, userId, tenantId, assistantMessageId, injectedBlocks, null);
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
        return sub(memory, query, injectedBlocks, userId, tenantId, null, null, null, 0);
    }

    /** Workflow 子 Agent — 含节点 params（skill / tools / overlay / maxIters） */
    public static AgentRunRequest sub(
            MemoryContext memory,
            String query,
            List<String> injectedBlocks,
            String userId,
            String tenantId,
            String skillId,
            List<String> toolWhitelist,
            String systemOverlay,
            int maxIters) {
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
                skillId,
                toolWhitelist,
                systemOverlay,
                maxIters,
                TimelineBinding.SUB_COMPRESSED);
    }

    /** Planner — 仅 plan 步 Timeline */
    public static AgentRunRequest planner(
            String query,
            String userId,
            String tenantId,
            String assistantMessageId) {
        return new AgentRunRequest(
                AgentRole.PLANNER,
                UUID.randomUUID().toString(),
                null,
                MemoryContext.empty(),
                query,
                List.of(),
                userId,
                tenantId,
                assistantMessageId,
                null,
                null,
                null,
                1,
                TimelineBinding.PLANNER_ONLY);
    }
}
