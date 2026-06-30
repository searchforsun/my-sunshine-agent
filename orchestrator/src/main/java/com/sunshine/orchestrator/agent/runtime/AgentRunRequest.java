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
        TimelineBinding timeline,
        boolean reactRestart
) {
    public AgentRunRequest {
        memory = memory != null ? memory : MemoryContext.empty();
        injectedBlocks = injectedBlocks != null ? List.copyOf(injectedBlocks) : List.of();
        toolWhitelist = toolWhitelist != null ? List.copyOf(toolWhitelist) : null;
    }

    /** MAIN 每 run 独立 main-{runId}；SUB 用 sub-{runId}（SSE 经 bindHitlBridge 映射 assistantMessageId） */
    public String resolveBridgeId() {
        if (role == AgentRole.SUB) {
            return "sub-" + runId;
        }
        return "main-" + runId;
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
                TimelineBinding.MAIN_FULL,
                false);
    }

    public static AgentRunRequest main(
            MemoryContext memory,
            String query,
            String userId,
            String tenantId,
            String assistantMessageId,
            List<String> injectedBlocks,
            String skillId,
            boolean reactRestart) {
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
                TimelineBinding.MAIN_FULL,
                reactRestart);
    }

    public static AgentRunRequest main(
            MemoryContext memory,
            String query,
            String userId,
            String tenantId,
            String assistantMessageId,
            List<String> injectedBlocks) {
        return main(memory, query, userId, tenantId, assistantMessageId, injectedBlocks, null, false);
    }

    public static AgentRunRequest main(
            MemoryContext memory, String query, String userId, String tenantId, String assistantMessageId) {
        return main(memory, query, userId, tenantId, assistantMessageId, List.of(), null, false);
    }

    /** Workflow 子 Agent — 不绑定 assistantMessageId，压缩 Timeline */
    public static AgentRunRequest sub(
            MemoryContext memory,
            String query,
            List<String> injectedBlocks,
            String userId,
            String tenantId) {
        return sub(memory, query, injectedBlocks, userId, tenantId, null, null, null, null, 0);
    }

    /** Workflow 子 Agent — 含节点 params（skill / tools / overlay / maxIters） */
    public static AgentRunRequest sub(
            MemoryContext memory,
            String query,
            List<String> injectedBlocks,
            String userId,
            String tenantId,
            String assistantMessageId,
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
                assistantMessageId,
                skillId,
                toolWhitelist,
                systemOverlay,
                maxIters,
                TimelineBinding.SUB_COMPRESSED,
                false);
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
                TimelineBinding.PLANNER_ONLY,
                false);
    }
}
