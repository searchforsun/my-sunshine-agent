package com.sunshine.orchestrator.agent.runtime;

import com.sunshine.orchestrator.context.AssembledContext;

import java.util.List;
import java.util.UUID;

/** 主/子 Agent 统一执行请求 */
public record AgentRunRequest(
        AgentRole role,
        String runId,
        String parentRunId,
        AssembledContext memory,
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
        boolean reactRestart,
        String reactPromptId,
        /** 对话级沙箱复用键；MAIN 必填方可跨 run 保留 workspace */
        String conversationId,
        /** ReAct checkpoint 续跑：中断前最大 think 轮次，用于 session.resumeFromCheckpoint */
        int checkpointThinkIteration,
        /** 知识库范围（覆盖会话级 kbId） */
        List<String> kbScope,
        /** 数据访问范围 */
        String dataScopeJson,
        /** 权限配置 */
        String permissionsJson,
        /** 模型配置（spawn / 预定义智能体）；优先于 modelOverride */
        String modelConfigJson,
        /** 会话级模型 override（仅 MAIN chat / Planner 主对话；intent/rewrite/title 忽略） */
        String modelOverride
) {
    public AgentRunRequest {
        memory = memory != null ? memory : AssembledContext.empty();
        injectedBlocks = injectedBlocks != null ? List.copyOf(injectedBlocks) : List.of();
        toolWhitelist = toolWhitelist != null ? List.copyOf(toolWhitelist) : null;
    }

    public AgentRunRequest withModelOverride(String modelOverride) {
        return new AgentRunRequest(
                role, runId, parentRunId, memory, query, injectedBlocks, userId, tenantId,
                assistantMessageId, skillId, toolWhitelist, systemOverlay, maxIters, timeline,
                reactRestart, reactPromptId, conversationId, checkpointThinkIteration,
                kbScope, dataScopeJson, permissionsJson, modelConfigJson, modelOverride);
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
            AssembledContext memory,
            String query,
            String userId,
            String tenantId,
            String assistantMessageId,
            List<String> injectedBlocks,
            String skillId) {
        return main(memory, query, userId, tenantId, assistantMessageId, injectedBlocks, skillId, false, null, null);
    }

    public static AgentRunRequest main(
            AssembledContext memory,
            String query,
            String userId,
            String tenantId,
            String assistantMessageId,
            List<String> injectedBlocks,
            String skillId,
            boolean reactRestart) {
        return main(memory, query, userId, tenantId, assistantMessageId, injectedBlocks, skillId, reactRestart, null, null);
    }

    public static AgentRunRequest main(
            AssembledContext memory,
            String query,
            String userId,
            String tenantId,
            String assistantMessageId,
            List<String> injectedBlocks,
            String skillId,
            boolean reactRestart,
            String conversationId) {
        return main(memory, query, userId, tenantId, assistantMessageId, injectedBlocks, skillId, reactRestart,
                conversationId, null);
    }

    public static AgentRunRequest main(
            AssembledContext memory,
            String query,
            String userId,
            String tenantId,
            String assistantMessageId,
            List<String> injectedBlocks,
            String skillId,
            boolean reactRestart,
            String conversationId,
            String reactPromptId,
            int checkpointThinkIteration) {
        return main(memory, query, userId, tenantId, assistantMessageId, injectedBlocks,
                skillId, reactRestart, conversationId, reactPromptId, checkpointThinkIteration, 0);
    }

    /** MAIN — 显式指定 ReAct 轮数上限（0 = 取 Nacos 默认） */
    public static AgentRunRequest main(
            AssembledContext memory,
            String query,
            String userId,
            String tenantId,
            String assistantMessageId,
            List<String> injectedBlocks,
            String skillId,
            boolean reactRestart,
            String conversationId,
            String reactPromptId,
            int checkpointThinkIteration,
            int maxIters) {
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
                maxIters,
                TimelineBinding.MAIN_FULL,
                reactRestart,
                reactPromptId,
                conversationId,
                checkpointThinkIteration,
                null,
                null,
                null,
                null,
                null);
    }

    public static AgentRunRequest main(
            AssembledContext memory,
            String query,
            String userId,
            String tenantId,
            String assistantMessageId,
            List<String> injectedBlocks,
            String skillId,
            boolean reactRestart,
            String conversationId,
            String reactPromptId) {
        return main(memory, query, userId, tenantId, assistantMessageId, injectedBlocks,
                skillId, reactRestart, conversationId, reactPromptId, 0);
    }

    public static AgentRunRequest main(
            AssembledContext memory,
            String query,
            String userId,
            String tenantId,
            String assistantMessageId,
            List<String> injectedBlocks) {
        return main(memory, query, userId, tenantId, assistantMessageId, injectedBlocks, null, false, null, null);
    }

    public static AgentRunRequest main(
            AssembledContext memory, String query, String userId, String tenantId, String assistantMessageId) {
        return main(memory, query, userId, tenantId, assistantMessageId, List.of(), null, false, null, null);
    }

    /** Workflow 子 Agent — 不绑定 assistantMessageId，压缩 Timeline */
    public static AgentRunRequest sub(
            AssembledContext memory,
            String query,
            List<String> injectedBlocks,
            String userId,
            String tenantId) {
        return sub(memory, query, injectedBlocks, userId, tenantId, null, null, null, null, 0);
    }

    /** Workflow 子 Agent — 含节点 params（skill / tools / overlay / maxIters） */
    public static AgentRunRequest sub(
            AssembledContext memory,
            String query,
            List<String> injectedBlocks,
            String userId,
            String tenantId,
            String assistantMessageId,
            String skillId,
            List<String> toolWhitelist,
            String systemOverlay,
            int maxIters) {
        return sub(
                memory, query, injectedBlocks, userId, tenantId, assistantMessageId,
                skillId, toolWhitelist, systemOverlay, maxIters, null);
    }

    /** Workflow 子 Agent — 含对话级沙箱复用键 */
    public static AgentRunRequest sub(
            AssembledContext memory,
            String query,
            List<String> injectedBlocks,
            String userId,
            String tenantId,
            String assistantMessageId,
            String skillId,
            List<String> toolWhitelist,
            String systemOverlay,
            int maxIters,
            String conversationId) {
        return sub(
                memory, query, injectedBlocks, userId, tenantId, assistantMessageId,
                skillId, toolWhitelist, systemOverlay, maxIters, conversationId,
                null, null, null, null);
    }

    /** SpawnSubagent 智能体配置版 */
    public static AgentRunRequest sub(
            AssembledContext memory,
            String query,
            List<String> injectedBlocks,
            String userId,
            String tenantId,
            String assistantMessageId,
            String skillId,
            List<String> toolWhitelist,
            String systemOverlay,
            int maxIters,
            String conversationId,
            List<String> kbScope,
            String dataScopeJson,
            String permissionsJson,
            String modelConfigJson) {
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
                false,
                null,
                conversationId,
                0,
                kbScope,
                dataScopeJson,
                permissionsJson,
                modelConfigJson,
                null);
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
                AssembledContext.empty(),
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
                false,
                null,
                null,
                0,
                null,
                null,
                null,
                null,
                null);
    }
}
