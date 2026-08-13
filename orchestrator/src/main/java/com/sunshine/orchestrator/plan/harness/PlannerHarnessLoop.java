package com.sunshine.orchestrator.plan.harness;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.catalog.ToolSetResolver;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Planner-Executor 单一循环：Plan → Validate → Execute → Assess + 预算熔断。
 * <p>
 * Worker 归属：{@link HarnessPlanner#planNext} 期间 Planner 可经 {@code dispatch_worker} 执行任务；
 * Loop 仅调度仍为 {@code pending}/{@code fail} 且 dependsOn 已 done 的任务，避免重复执行。
 * RoundRecord 仍只由 {@link WorkerDispatchTool} 追加。
 * <p>
 * {@code maxRounds} SSOT = Plan 波次（本类本地 {@code wave}），不是 {@code notebook.currentRound}
 *（后者随 Worker RoundRecord 递增）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlannerHarnessLoop {

    private final HarnessPlanner planner;
    private final WorkerDispatchTool workerDispatchTool;
    private final PlanNotebookStore store;
    private final AgentExecutionProperties executionProperties;
    private final ToolSetResolver toolSetResolver;

    public Flux<StreamToken> run(ExecutionStreamContext ctx, PlanNotebook notebook) {
        return Flux.defer(() -> {
            Instant startedAt = Instant.now();
            int wave = 0;
            List<StreamToken> emitted = new ArrayList<>();
            GoalAlignmentValidator alignmentValidator = new GoalAlignmentValidator(
                    executionProperties.getHarness().getStaleRoundsThreshold());
            // 与 HarnessPlanner.runId 区分：Loop 直调 Worker 用独立 parentRunId，避免与 assistantMsgId 碰撞
            String loopRunId = "harness-loop-" + (ctx != null && StringUtils.hasText(ctx.assistantMsgId())
                    ? ctx.assistantMsgId()
                    : UUID.randomUUID());
            while (true) {
                if (budgetExhausted(notebook, startedAt, wave)) {
                    log.info("[PlannerHarnessLoop] 预算熔断 session={} wave={} round={} replans={} tasks={}",
                            notebook.getSessionId(), wave, notebook.getCurrentRound(),
                            notebook.getReplanCount(), notebook.getTotalTasksCompleted());
                    store.save(notebook);
                    return Flux.fromIterable(emitted).concatWith(planner.synthesizeAnswer(notebook, ctx));
                }
                wave++;
                String planStepId = wave == 1 ? "plan" : "plan-R" + wave;
                emitted.add(StreamToken.step(ProcessingStep.running(
                        planStepId, "plan", "规划 R" + wave)));
                try {
                    planner.planNext(notebook, ctx);
                } catch (RuntimeException e) {
                    log.warn("[PlannerHarnessLoop] planNext 失败，综合已有结果: {}", e.getMessage());
                    emitted.add(StreamToken.step(ProcessingStep.done(
                            planStepId, "plan", "规划 R" + wave, "planNext 失败")));
                    store.save(notebook);
                    return Flux.fromIterable(emitted).concatWith(planner.synthesizeAnswer(notebook, ctx));
                }
                Optional<String> validationError = TaskQueueValidator.validate(
                        List.copyOf(notebook.getTaskQueue()));
                if (validationError.isPresent()) {
                    log.warn("[PlannerHarnessLoop] taskQueue 校验失败: {}", validationError.get());
                    notebook.setReplanCount(notebook.getReplanCount() + 1);
                    emitted.add(StreamToken.step(ProcessingStep.done(
                            planStepId, "plan", "规划 R" + wave, "校验失败，重规划")));
                    store.save(notebook);
                    continue;
                }
                emitted.add(StreamToken.step(ProcessingStep.done(
                        planStepId, "plan", "规划 R" + wave,
                        summarizeQueue(notebook))));
                boolean workerFailedExhausted = false;
                for (TaskItem ready : selectReadyPendingTasks(notebook)) {
                    String workerStepId = "worker-" + ready.taskId();
                    emitted.add(StreamToken.step(ProcessingStep.running(
                            workerStepId, "worker", ready.label())));
                    boolean ok = executeTaskWithRetries(notebook, ctx, ready.taskId(), loopRunId);
                    TaskItem after = findTask(notebook, ready.taskId());
                    String detail = after != null ? after.status() : "missing";
                    emitted.add(StreamToken.step(ProcessingStep.done(
                            workerStepId, "worker", ready.label(), detail)));
                    if (!ok) {
                        workerFailedExhausted = true;
                        break;
                    }
                }
                if (workerFailedExhausted) {
                    notebook.setReplanCount(notebook.getReplanCount() + 1);
                    log.info("[PlannerHarnessLoop] Worker 重试耗尽 → replan count={}",
                            notebook.getReplanCount());
                    store.save(notebook);
                    continue;
                }
                double completionBefore = notebook.getGoalCompletion();
                try {
                    planner.selfAssess(notebook, ctx);
                } catch (RuntimeException e) {
                    log.warn("[PlannerHarnessLoop] selfAssess 失败，综合已有结果: {}", e.getMessage());
                    store.save(notebook);
                    return Flux.fromIterable(emitted).concatWith(planner.synthesizeAnswer(notebook, ctx));
                }
                if (notebook.getGoalCompletion() <= completionBefore) {
                    notebook.setStaleRounds(notebook.getStaleRounds() + 1);
                } else {
                    notebook.setStaleRounds(0);
                }
                // DEVIATED/STUCK 必须在 Execute+Assess 之后：历史低完成度不能饿死本波 Worker
                GoalAlignmentValidator.Alignment alignment = alignmentValidator.assess(notebook);
                if (alignment == GoalAlignmentValidator.Alignment.STUCK) {
                    log.info("[PlannerHarnessLoop] GoalAlignment STUCK → synthesize");
                    store.save(notebook);
                    return Flux.fromIterable(emitted).concatWith(planner.synthesizeAnswer(notebook, ctx));
                }
                if (alignment == GoalAlignmentValidator.Alignment.DEVIATED) {
                    log.info("[PlannerHarnessLoop] GoalAlignment DEVIATED → replan");
                    notebook.setReplanCount(notebook.getReplanCount() + 1);
                    store.save(notebook);
                    continue;
                }
                store.save(notebook);
                AssessDecision decision = resolveAssessDecision(notebook);
                if (decision == AssessDecision.ANSWER) {
                    log.info("[PlannerHarnessLoop] nextDirection={} goalCompletion={} → synthesize",
                            notebook.getNextDirection(), notebook.getGoalCompletion());
                    return Flux.fromIterable(emitted).concatWith(planner.synthesizeAnswer(notebook, ctx));
                }
                if (decision == AssessDecision.REPLAN) {
                    notebook.setReplanCount(notebook.getReplanCount() + 1);
                    log.info("[PlannerHarnessLoop] nextDirection=replan count={}",
                            notebook.getReplanCount());
                    store.save(notebook);
                    continue;
                }
            }
        });
    }

    enum AssessDecision {
        CONTINUE,
        REPLAN,
        ANSWER
    }

    /**
     * Catalog 契约：{@code nextDirection = continue|replan|answer}；
     * {@code done} 视为 answer 别名；空白时仅当 goalCompletion≥1.0 视为完成。
     */
    static AssessDecision resolveAssessDecision(PlanNotebook notebook) {
        String raw = notebook.getNextDirection();
        String direction = StringUtils.hasText(raw) ? raw.strip().toLowerCase(Locale.ROOT) : "";
        if ("answer".equals(direction) || "done".equals(direction)) {
            return AssessDecision.ANSWER;
        }
        if ("replan".equals(direction)) {
            return AssessDecision.REPLAN;
        }
        if (notebook.getGoalCompletion() >= 1.0) {
            return AssessDecision.ANSWER;
        }
        return AssessDecision.CONTINUE;
    }

    private boolean executeTaskWithRetries(
            PlanNotebook notebook, ExecutionStreamContext ctx, String taskId, String loopRunId) {
        int maxRetries = Math.max(0, executionProperties.getHarness().getTask().getMaxRetries());
        int maxAttempts = maxRetries + 1;
        WorkerDispatchTool.DispatchSession session = bindDispatchSession(notebook, ctx, loopRunId);
        try {
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                TaskItem current = findTask(notebook, taskId);
                if (current == null) {
                    return false;
                }
                if ("done".equals(current.status())) {
                    return true;
                }
                if (!"pending".equals(current.status()) && !"fail".equals(current.status())) {
                    return "done".equals(current.status());
                }
                if ("fail".equals(current.status())) {
                    WorkerDispatchTool.replaceTaskStatus(notebook, taskId, "pending");
                }
                workerDispatchTool.dispatchWorker(taskId, session);
                TaskItem after = findTask(notebook, taskId);
                if (after != null && "done".equals(after.status())) {
                    return true;
                }
                log.warn("[PlannerHarnessLoop] Worker 失败 taskId={} attempt={}/{}",
                        taskId, attempt, maxAttempts);
            }
            return false;
        } finally {
            WorkerDispatchTool.clearSession(session);
        }
    }

    private WorkerDispatchTool.DispatchSession bindDispatchSession(
            PlanNotebook notebook, ExecutionStreamContext ctx, String loopRunId) {
        String tenantId = ctx != null ? ctx.tenantId() : null;
        List<String> whitelist = toolSetResolver.resolveReactTools(tenantId);
        WorkerDispatchTool.DispatchSession session = new WorkerDispatchTool.DispatchSession(
                notebook,
                whitelist != null ? whitelist : List.of(),
                ctx != null ? ctx.userId() : null,
                tenantId,
                ctx != null ? ctx.assistantMsgId() : null,
                ctx != null ? ctx.conversationId() : null,
                loopRunId,
                0);
        WorkerDispatchTool.bindSession(session);
        return session;
    }

    static List<TaskItem> selectReadyPendingTasks(PlanNotebook notebook) {
        Set<String> doneIds = new HashSet<>();
        for (TaskItem item : notebook.getTaskQueue()) {
            if ("done".equals(item.status())) {
                doneIds.add(item.taskId());
            }
        }
        List<TaskItem> ready = new ArrayList<>();
        for (TaskItem item : notebook.getTaskQueue()) {
            if (!"pending".equals(item.status()) && !"fail".equals(item.status())) {
                continue;
            }
            List<String> deps = item.dependsOn() != null ? item.dependsOn() : List.of();
            boolean depsMet = true;
            for (String dep : deps) {
                if (!doneIds.contains(dep)) {
                    depsMet = false;
                    break;
                }
            }
            if (depsMet) {
                ready.add(item);
            }
        }
        return ready;
    }

    private boolean budgetExhausted(PlanNotebook notebook, Instant startedAt, int wave) {
        AgentExecutionProperties.Harness harness = executionProperties.getHarness();
        if (harness.getMaxDurationMs() > 0
                && Duration.between(startedAt, Instant.now()).toMillis() >= harness.getMaxDurationMs()) {
            return true;
        }
        // maxRounds = Plan 波次上限（wave），非 Worker RoundRecord 条数
        int maxRounds = notebook.getMaxRounds() > 0 ? notebook.getMaxRounds() : harness.getMaxRounds();
        if (maxRounds > 0 && wave >= maxRounds) {
            return true;
        }
        int maxReplans = harness.getPlanner().getMaxReplans();
        if (maxReplans > 0 && notebook.getReplanCount() >= maxReplans) {
            return true;
        }
        int maxTotalTasks = notebook.getMaxTotalTasks() > 0
                ? notebook.getMaxTotalTasks()
                : harness.getMaxTotalTasks();
        if (maxTotalTasks > 0 && notebook.getTotalTasksCompleted() >= maxTotalTasks) {
            return true;
        }
        return false;
    }

    private static TaskItem findTask(PlanNotebook notebook, String taskId) {
        for (TaskItem item : notebook.getTaskQueue()) {
            if (taskId.equals(item.taskId())) {
                return item;
            }
        }
        return null;
    }

    private static String summarizeQueue(PlanNotebook notebook) {
        int n = notebook.getTaskQueue().size();
        return n + " 个调度单元";
    }
}
