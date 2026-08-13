package com.sunshine.orchestrator.plan.harness;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.ReactExecutor;
import com.sunshine.orchestrator.plan.PlanTimeline;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.ProcessingTimelineSupport;
import com.sunshine.orchestrator.skill.SkillBindingOutcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * Planner-Executor 主入口（routing v6 / H-5）：用户 {@code ExecutionMode.PRO} 且
 * {@code harness.enabled=true} 时由 {@link com.sunshine.orchestrator.execution.ExecutionDispatcher}
 * （ResourceDispatcher）分发至此；harness 关闭时 Dispatcher 显式失败，不回落旧动态规划。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlannerHarnessExecutor {

    private final PlanNotebookStore store;
    private final PlannerHarnessLoop loop;
    private final ReactExecutor reactExecutor;
    private final AgentExecutionProperties executionProperties;

    public Flux<StreamToken> execute(ExecutionStreamContext ctx) {
        String sessionId = resolveSessionId(ctx);
        PlanNotebook notebook = store.load(sessionId).orElseGet(() -> createNotebook(ctx, sessionId));
        if (!StringUtils.hasText(notebook.getSessionId())) {
            notebook.setSessionId(sessionId);
        }
        applyFollowUpGoalChange(notebook, resolveQuery(ctx));
        return loop.run(ctx, notebook)
                .doFinally(signal -> store.renewTtl(sessionId))
                .onErrorResume(e -> fallbackOrPropagate(ctx, e));
    }

    /**
     * S6 ③：本轮 query 与 notebook 目标不一致 → 更新 goal，非 done 任务标 obsolete。
     */
    static void applyFollowUpGoalChange(PlanNotebook notebook, String newQuery) {
        if (notebook == null) {
            return;
        }
        String next = newQuery != null ? newQuery.strip() : "";
        String prev = StringUtils.hasText(notebook.getOriginalGoal())
                ? notebook.getOriginalGoal().strip()
                : (notebook.getUserQuery() != null ? notebook.getUserQuery().strip() : "");
        if (next.isEmpty() || next.equals(prev)) {
            return;
        }
        notebook.setOriginalGoal(next);
        notebook.setUserQuery(next);
        for (TaskItem item : List.copyOf(notebook.getTaskQueue())) {
            if (item == null || "done".equals(item.status())) {
                continue;
            }
            WorkerDispatchTool.replaceTaskStatus(notebook, item.taskId(), "obsolete");
        }
        notebook.setReplanCount(notebook.getReplanCount() + 1);
    }

    private Flux<StreamToken> fallbackOrPropagate(ExecutionStreamContext ctx, Throwable error) {
        if (!executionProperties.getHarness().getFallbackReact().isEnabled()) {
            return Flux.error(error);
        }
        log.warn("[PlannerHarnessExecutor] harness 终态失败，降级 react: {}", error.getMessage());
        ProcessingTimelineSession session = ProcessingTimelineSupport.newSession();
        session.bindUserQuery(ctx.userContent());
        session.bindTraceMessageId(ctx.assistantMsgId());
        List<StreamToken> fallbackPlan = PlanTimeline.planFallbackStep(
                session, "Planner-Executor 未完成：" + error.getMessage() + "；改由自主智能体执行");
        return Flux.concat(Flux.fromIterable(fallbackPlan), reactExecutor.execute(ctx));
    }

    private PlanNotebook createNotebook(ExecutionStreamContext ctx, String sessionId) {
        AgentExecutionProperties.Harness harness = executionProperties.getHarness();
        String query = resolveQuery(ctx);
        PlanNotebook notebook = PlanNotebook.create(
                query,
                query,
                ctx.conversationKind(),
                harness.getMaxRounds(),
                harness.getMaxTotalTasks());
        notebook.setSessionId(sessionId);
        return notebook;
    }

    private static String resolveSessionId(ExecutionStreamContext ctx) {
        if (StringUtils.hasText(ctx.conversationId())) {
            return ctx.conversationId();
        }
        return ctx.assistantMsgId();
    }

    private static String resolveQuery(ExecutionStreamContext ctx) {
        Map<String, String> params = ctx.plan() != null && ctx.plan().params() != null
                ? ctx.plan().params() : Map.of();
        String effective = params.get(SkillBindingOutcome.PARAM_EFFECTIVE_QUERY);
        if (StringUtils.hasText(effective)) {
            return effective.strip();
        }
        return ctx.userContent() != null ? ctx.userContent() : "";
    }
}
