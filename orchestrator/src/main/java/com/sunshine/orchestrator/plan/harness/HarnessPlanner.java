package com.sunshine.orchestrator.plan.harness;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.ProcessingStepSerde;
import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.agent.runtime.AgentRuntime;
import com.sunshine.orchestrator.catalog.ToolSetResolver;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.context.ContextAssembler;
import com.sunshine.orchestrator.execution.DecisionResumeSteps;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.generation.GenerationRegistry;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Planner-Executor 单一入口：{@link #runPlanned} 一次性 ReAct run。
 * <p>
 * 架构决议（v17）：Planner 不再拆为 planNext / selfAssess / synthesizeAnswer 三次 ReAct；
 * 视为「带 plan_submit / dispatch_worker / self_assess 元工具的普通 ReAct」，
 * 与 Subagent 同构：think + tool_call + think + tool_call + … + 最终 content。
 * 时间线表现：Planner 自己的 think 与元工具调用全部平铺在主时间线（不折叠为独立"评估进展"步），
 * 每个 dispatch_worker 触发一次 Worker run（独立 worker-{taskId} 卡）。
 * <p>
 * 任务状态：taskQueue 由 plan_submit 写入并实时刷新 TaskBoard；
 * RoundRecord 仍只由 {@link WorkerDispatchTool} 在 Worker 完成/失败时追加。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HarnessPlanner {

    public static final String CATALOG_ID = "planner.harness";

    private final AgentRuntime agentRuntime;
    private final ContextAssembler contextAssembler;
    private final AgentExecutionProperties executionProperties;
    private final ToolSetResolver toolSetResolver;
    private final PromptCatalogHolder catalogHolder;
    /** 续跑 decision：stepsBuffer 优先（与 ReactExecutor 同源），惰性注入避免循环依赖 */
    private final ObjectProvider<GenerationRegistry> generationRegistry;

    /**
     * 一次性 Planner ReAct run。tokens 流式透传主时间线：
     * - think → 独立 think 步（由 ProcessingStepMiddleware 渲染）
     * - plan_submit / self_assess → tool 步（与普通工具调用同构）
     * - dispatch_worker → worker-{taskId} 卡（Worker 独立 run，由 WorkerDispatchTool 渲染）
     * - 终态 content → 主时间线正文（综合回答）
     */
    public Flux<StreamToken> runPlanned(PlanNotebook notebook, ExecutionStreamContext ctx) {
        AgentRunRequest request = buildRequest(notebook, ctx);
        // D12：Planner 续跑存在 awaiting/paused decision 卡时 bind steps，
        // ReActAgentRuntime bridge bind 后 re-await 同问卷（契约同 Chat MAIN，见 DecisionResumeSupport）
        bindDecisionResumeIfNeeded(ctx);
        WorkerDispatchTool.DispatchSession session = bindDispatchSession(notebook, ctx, request.runId(), request.skillId());
        // session 保持绑定到 Planner run 结束；Worker dispatch 工具按 toolUse 解析 session
        return agentRuntime.run(request)
                .doFinally(sig -> WorkerDispatchTool.clearSession(session));
    }

    /**
     * D12：仅当 assistantMessageId 存在且可解析出待决 decision 步时才 bind；
     * DecisionResumeSteps 为空则 ReActAgentRuntime 侧 take 落空，正常新 run 不受影响。
     */
    private void bindDecisionResumeIfNeeded(ExecutionStreamContext ctx) {
        if (ctx == null || !StringUtils.hasText(ctx.assistantMsgId())) {
            return;
        }
        List<ProcessingStep> steps = resolveDecisionResumeSteps(ctx);
        if (!steps.isEmpty()) {
            DecisionResumeSteps.bind(ctx.assistantMsgId().strip(), steps);
        }
    }

    /** 优先 GenerationJob stepsBuffer（含保留的 decision 卡），否则 existingStepsJson；解析失败不阻断 Planner run */
    private List<ProcessingStep> resolveDecisionResumeSteps(ExecutionStreamContext ctx) {
        String msgId = ctx.assistantMsgId().strip();
        GenerationRegistry registry = generationRegistry.getIfAvailable();
        if (registry != null) {
            var jobOpt = registry.findByMessageId(msgId);
            if (jobOpt.isPresent()) {
                List<ProcessingStep> buffered = jobOpt.get().getStepsBuffer();
                if (buffered != null && !buffered.isEmpty()) {
                    return List.copyOf(buffered);
                }
            }
        }
        if (ctx.existingStepsJson() == null || ctx.existingStepsJson().isBlank()) {
            return List.of();
        }
        try {
            return List.copyOf(ProcessingStepSerde.fromJson(ctx.existingStepsJson()));
        } catch (Exception e) {
            log.warn("[HarnessPlanner] 续跑 steps 解析失败 msg={}: {}", msgId, e.getMessage());
            return List.of();
        }
    }

    private AgentRunRequest buildRequest(PlanNotebook notebook, ExecutionStreamContext ctx) {
        AssembledContext memory = resolveMemory(ctx);
        int nearKeep = executionProperties.getHarness().getNotebook().getCompression().getNearKeepRounds();
        List<String> injected = List.of(notebook.renderForPlanner(nearKeep));
        String query = buildQuery(ctx);
        int maxIters = executionProperties.getHarness().getPlanner().getMaxIters() > 0
                ? executionProperties.getHarness().getPlanner().getMaxIters()
                : 50;
        return AgentRunRequest.planner(
                        memory,
                        query,
                        injected,
                        ctx.userId(),
                        ctx.tenantId(),
                        ctx.assistantMsgId(),
                        ctx.conversationId(),
                        maxIters)
                .withConversationKind(resolveConversationKind(notebook, ctx));
    }

    private AssembledContext resolveMemory(ExecutionStreamContext ctx) {
        AssembledContext provided = ctx != null ? ctx.memory() : null;
        if (provided != null && !isBare(provided)) {
            return provided;
        }
        if (ctx != null && StringUtils.hasText(ctx.conversationId()) && StringUtils.hasText(ctx.userId())) {
            try {
                AssembledContext assembled = contextAssembler.assemble(new ContextAssembler.AssembleRequest(
                        ctx.userId(),
                        ctx.tenantId(),
                        ctx.conversationId(),
                        List.of(),
                        ctx.userContent(),
                        null, "chat", null, null, false));
                if (assembled != null) {
                    return assembled;
                }
            } catch (Exception e) {
                log.warn("[HarnessPlanner] ContextAssembler.assemble 失败 conv={}: {}",
                        ctx.conversationId(), e.getMessage());
            }
        }
        return provided != null ? provided : AssembledContext.empty();
    }

    private WorkerDispatchTool.DispatchSession bindDispatchSession(
            PlanNotebook notebook, ExecutionStreamContext ctx, String parentRunId, String skillId) {
        String tenantId = ctx != null ? ctx.tenantId() : null;
        List<String> whitelist = toolSetResolver.resolveDefaultTools(tenantId, resolveConversationKind(notebook, ctx));
        WorkerDispatchTool.DispatchSession session = new WorkerDispatchTool.DispatchSession(
                notebook,
                whitelist != null ? whitelist : List.of(),
                ctx != null ? ctx.userId() : null,
                tenantId,
                ctx != null ? ctx.assistantMsgId() : null,
                ctx != null ? ctx.conversationId() : null,
                parentRunId,
                0,
                resolveConversationKind(notebook, ctx),
                skillId);
        WorkerDispatchTool.bindSession(session);
        return session;
    }

    /** notebook.kind 优先，否则 stream ctx，缺省 chat */
    private static String resolveConversationKind(PlanNotebook notebook, ExecutionStreamContext ctx) {
        if (notebook != null && StringUtils.hasText(notebook.getKind())) {
            return notebook.getKind().strip();
        }
        if (ctx != null && StringUtils.hasText(ctx.conversationKind())) {
            return ctx.conversationKind().strip();
        }
        return "chat";
    }

    /**
     * 合并去重地更新 taskQueue：保留已完成/已失败/已废弃的条目（同 taskId 不覆盖）；
     * 新提交中未出现的旧 pending/in_progress 任务标记为 obsolete（被新一轮规划撤下）；
     * 新提交中的条目按出现顺序插入或覆盖同 id。
     *
     * <p>v17.11：失败/取消历史**更硬保留**——同名新提交（pending）不覆盖 fail/cancelled 旧条目，
     * 保证 Planner 续跑重派经 {@code dispatch_worker} 传 base taskId 时能命中旧记录自动版本化
     * （t1 → t1-2），否则取消历史被覆盖、重派退化为同名 t1 原地执行、TaskBoard 无法区分尝试次数。
     */
    static int mergeTaskQueue(PlanNotebook notebook, List<TaskItem> tasks) {
        if (notebook == null || notebook.getTaskQueue() == null) {
            return 0;
        }
        java.util.LinkedHashMap<String, TaskItem> byId = new java.util.LinkedHashMap<>();
        for (TaskItem t : notebook.snapshotQueue()) {
            byId.put(t.taskId(), t);
        }
        java.util.Set<String> incomingIds = new java.util.HashSet<>();
        if (tasks != null) {
            for (TaskItem t : tasks) {
                incomingIds.add(t.taskId());
                TaskItem existing = byId.get(t.taskId());
                if (existing != null && isTerminalFailure(existing.status())) {
                    continue;
                }
                byId.put(t.taskId(), t);
            }
        }
        int obsoleted = 0;
        for (java.util.Map.Entry<String, TaskItem> e : byId.entrySet()) {
            if (incomingIds.contains(e.getKey())) continue;
            TaskItem t = e.getValue();
            String s = t.status();
            if ("done".equals(s) || "fail".equals(s) || "cancelled".equals(s) || "obsolete".equals(s)) continue;
            byId.put(e.getKey(), new TaskItem(
                    t.taskId(), t.label(), "obsolete",
                    t.dependsOn(), t.constraints(), t.expectedOutput(), t.successCriteria(),
                    t.baseTaskId(), t.retryIndex(), t.parentTaskId(), t.failReason()));
            obsoleted++;
        }
        notebook.replaceQueue(new java.util.ArrayList<>(byId.values()));
        return obsoleted;
    }

    private String buildQuery(ExecutionStreamContext ctx) {
        // 角色定位 SSOT：DB `planner.harness`（线上 prompt_definition / prompt_version，热更新）。
        // 不再使用硬编码 PLANNER_HINT；查询内容由用户在 query 字段传入。
        return ctx != null && StringUtils.hasText(ctx.userContent()) ? ctx.userContent().strip() : "";
    }

    /** 暴露给外部调用：当前 Catalog 上的 planner.harness 文本（供调试 / 测试） */
    public String plannerHintText() {
        return catalogHolder.requireText(CATALOG_ID);
    }

    private static boolean isTerminalFailure(String status) {
        return "fail".equals(status) || "cancelled".equals(status);
    }

    private static boolean isBare(AssembledContext memory) {
        return memory.nearTurns().isEmpty()
                && memory.midTurns().isEmpty()
                && !StringUtils.hasText(memory.l2SystemBlock())
                && !StringUtils.hasText(memory.farSummaryBlock())
                && !StringUtils.hasText(memory.l3MaterialBlock())
                && !StringUtils.hasText(memory.projectGuideBlock());
    }
}
