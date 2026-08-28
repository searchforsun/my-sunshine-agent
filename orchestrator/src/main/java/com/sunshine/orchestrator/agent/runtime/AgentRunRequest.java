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
        /** Planner-Executor harness overlay id（仅 PLANNER；机制层，见 PromptComposer.resolveHarnessOverlay） */
        String harnessPromptId,
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
        String modelOverride,
        /** 会话 kind（chat|task）；装默认工具集透传，缺省按 chat */
        String conversationKind,
        /** 本轮已触发 skill 集（仅 MAIN；skill-sticky S-T，SUB/Worker 用单数 skillId） */
        List<String> triggeredSkillIds,
        /** 本轮候选 skill 集（仅 MAIN；S-C：目录提权 + dynamicLoadable，可经 sunshine_search_skills 升级触发） */
        List<String> candidateSkillIds
) {
    public AgentRunRequest {
        memory = memory != null ? memory : AssembledContext.empty();
        injectedBlocks = injectedBlocks != null ? List.copyOf(injectedBlocks) : List.of();
        toolWhitelist = toolWhitelist != null ? List.copyOf(toolWhitelist) : null;
        triggeredSkillIds = triggeredSkillIds != null ? List.copyOf(triggeredSkillIds) : List.of();
        candidateSkillIds = candidateSkillIds != null ? List.copyOf(candidateSkillIds) : List.of();
    }

    public AgentRunRequest withModelOverride(String modelOverride) {
        return new AgentRunRequest(
                role, runId, parentRunId, memory, query, injectedBlocks, userId, tenantId,
                assistantMessageId, skillId, toolWhitelist, systemOverlay, maxIters, timeline,
                reactRestart, harnessPromptId, conversationId, checkpointThinkIteration,
                kbScope, dataScopeJson, permissionsJson, modelConfigJson, modelOverride,
                conversationKind, triggeredSkillIds, candidateSkillIds);
    }

    /** 透传会话 kind（装默认工具集）；不查库 */
    public AgentRunRequest withConversationKind(String conversationKind) {
        return new AgentRunRequest(
                role, runId, parentRunId, memory, query, injectedBlocks, userId, tenantId,
                assistantMessageId, skillId, toolWhitelist, systemOverlay, maxIters, timeline,
                reactRestart, harnessPromptId, conversationId, checkpointThinkIteration,
                kbScope, dataScopeJson, permissionsJson, modelConfigJson, modelOverride,
                conversationKind, triggeredSkillIds, candidateSkillIds);
    }

    /** 本轮已触发 skill 集（MAIN；skill-sticky S-T） */
    public AgentRunRequest withTriggeredSkillIds(List<String> triggeredSkillIds) {
        return new AgentRunRequest(
                role, runId, parentRunId, memory, query, injectedBlocks, userId, tenantId,
                assistantMessageId, skillId, toolWhitelist, systemOverlay, maxIters, timeline,
                reactRestart, harnessPromptId, conversationId, checkpointThinkIteration,
                kbScope, dataScopeJson, permissionsJson, modelConfigJson, modelOverride,
                conversationKind, triggeredSkillIds, candidateSkillIds);
    }

    /** 本轮候选 skill 集（MAIN；skill-sticky S-C） */
    public AgentRunRequest withCandidateSkillIds(List<String> candidateSkillIds) {
        return new AgentRunRequest(
                role, runId, parentRunId, memory, query, injectedBlocks, userId, tenantId,
                assistantMessageId, skillId, toolWhitelist, systemOverlay, maxIters, timeline,
                reactRestart, harnessPromptId, conversationId, checkpointThinkIteration,
                kbScope, dataScopeJson, permissionsJson, modelConfigJson, modelOverride,
                conversationKind, triggeredSkillIds, candidateSkillIds);
    }

    /** Planner-Executor harness overlay（机制层）；仅 PLANNER 使用 */
    public AgentRunRequest withHarnessPromptId(String harnessPromptId) {
        return new AgentRunRequest(
                role, runId, parentRunId, memory, query, injectedBlocks, userId, tenantId,
                assistantMessageId, skillId, toolWhitelist, systemOverlay, maxIters, timeline,
                reactRestart, harnessPromptId, conversationId, checkpointThinkIteration,
                kbScope, dataScopeJson, permissionsJson, modelConfigJson, modelOverride,
                conversationKind, triggeredSkillIds, candidateSkillIds);
    }

    /** MAIN 每 run 独立 main-{runId}；SUB/WORKER 用角色前缀（SSE 经 bindHitlBridge 映射 assistantMessageId） */
    public String resolveBridgeId() {
        if (role == AgentRole.SUB) {
            return "sub-" + runId;
        }
        if (role == AgentRole.WORKER) {
            return "worker-" + runId;
        }
        if (role == AgentRole.PLANNER) {
            return "planner-" + runId;
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
        return main(memory, query, userId, tenantId, assistantMessageId, injectedBlocks, skillId, false, null, 0);
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
        return main(memory, query, userId, tenantId, assistantMessageId, injectedBlocks, skillId, reactRestart, null, 0);
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
                conversationId, 0);
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
            int checkpointThinkIteration) {
        return main(memory, query, userId, tenantId, assistantMessageId, injectedBlocks,
                skillId, reactRestart, conversationId, checkpointThinkIteration, 0);
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
                null,
                conversationId,
                checkpointThinkIteration,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /** MAIN — 含本轮已触发 skill 集（skill-sticky S-T）；null 兼容旧调用 */
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
            int checkpointThinkIteration,
            int maxIters,
            List<String> triggeredSkillIds) {
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
                null,
                conversationId,
                checkpointThinkIteration,
                null,
                null,
                null,
                null,
                null,
                null,
                triggeredSkillIds,
                null);
    }

    public static AgentRunRequest main(
            AssembledContext memory,
            String query,
            String userId,
            String tenantId,
            String assistantMessageId,
            List<String> injectedBlocks) {
        return main(memory, query, userId, tenantId, assistantMessageId, injectedBlocks, null, false, null, 0);
    }

    public static AgentRunRequest main(
            AssembledContext memory, String query, String userId, String tenantId, String assistantMessageId) {
        return main(memory, query, userId, tenantId, assistantMessageId, List.of(), null, false, null, 0);
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
                null,
                null,
                null,
                null);
    }

    /** Planner-Executor — ReAct + Catalog {@code planner.harness}；H1 经 injectedBlocks */
    public static AgentRunRequest planner(
            String query,
            String userId,
            String tenantId,
            String assistantMessageId) {
        return planner(AssembledContext.empty(), query, List.of(), userId, tenantId, assistantMessageId, null, 0);
    }

    public static AgentRunRequest planner(
            AssembledContext memory,
            String query,
            List<String> injectedBlocks,
            String userId,
            String tenantId,
            String assistantMessageId,
            String conversationId,
            int maxIters) {
        return new AgentRunRequest(
                AgentRole.PLANNER,
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
                maxIters,
                TimelineBinding.PLANNER_ONLY,
                false,
                HARNESS_PROMPT_ID,
                conversationId,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /** Planner-Executor harness overlay id（机制层；PromptComposer 仅接受 kind=planner） */
    public static final String HARNESS_PROMPT_ID = "planner.harness";

    /** Planner-Executor Worker — forWorker 记忆 + 工具白名单 + WORKER_NESTED Timeline */
    public static AgentRunRequest worker(
            AssembledContext memory,
            String query,
            List<String> toolWhitelist,
            String userId,
            String tenantId,
            String assistantMessageId,
            String conversationId,
            int maxIters,
            String parentRunId) {
        return new AgentRunRequest(
                AgentRole.WORKER,
                UUID.randomUUID().toString(),
                parentRunId,
                memory,
                query,
                List.of(),
                userId,
                tenantId,
                assistantMessageId,
                null,
                toolWhitelist,
                null,
                maxIters,
                TimelineBinding.WORKER_NESTED,
                false,
                null,
                conversationId,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
