package com.sunshine.orchestrator.plan.harness;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.catalog.ToolSetResolver;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Planner-Executor 单一循环：Plan → Validate → Execute → Assess + 预算熔断。
 * <p>
 * Worker 归属：{@link HarnessPlanner#planNext} 期间 Planner 可经 {@code dispatch_worker} 执行任务；
 * Loop 仅调度仍为 {@code pending} 且 dependsOn 已 done 的任务，避免重复执行。
 * RoundRecord 仍只由 {@link WorkerDispatchTool} 追加。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlannerHarnessLoop {

    private static final double GOAL_DONE_THRESHOLD = 1.0;

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
                GoalAlignmentValidator.Alignment alignment = alignmentValidator.assess(notebook);
                if (alignment == GoalAlignmentValidator.Alignment.STUCK) {
                    log.info("[PlannerHarnessLoop] GoalAlignment STUCK → synthesize");
                    emitted.add(StreamToken.step(ProcessingStep.done(
                            planStepId, "plan", "规划 R" + wave, "STUCK")));
                    store.save(notebook);
                    return Flux.fromIterable(emitted).concatWith(planner.synthesizeAnswer(notebook, ctx));
                }
                if (alignment == GoalAlignmentValidator.Alignment.DEVIATED) {
                    log.info("[PlannerHarnessLoop] GoalAlignment DEVIATED → replan");
                    notebook.setReplanCount(notebook.getReplanCount() + 1);
                    emitted.add(StreamToken.step(ProcessingStep.done(
                            planStepId, "plan", "规划 R" + wave, "DEVIATED，重规划")));
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
                    boolean ok = executeTaskWithRetries(notebook, ctx, ready.taskId());
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
                    log.warn("[PlannerHarnessLoop] selfAssess 失败: {}", e.getMessage());
                }
                if (notebook.getGoalCompletion() <= completionBefore) {
                    notebook.setStaleRounds(notebook.getStaleRounds() + 1);
                } else {
                    notebook.setStaleRounds(0);
                }
                store.save(notebook);
                if (notebook.getGoalCompletion() >= GOAL_DONE_THRESHOLD) {
                    log.info("[PlannerHarnessLoop] goalCompletion={} → synthesize",
                            notebook.getGoalCompletion());
                    return Flux.fromIterable(emitted).concatWith(planner.synthesizeAnswer(notebook, ctx));
                }
            }
        });
    }

    private boolean executeTaskWithRetries(PlanNotebook notebook, ExecutionStreamContext ctx, String taskId) {
        int maxRetries = Math.max(0, executionProperties.getHarness().getTask().getMaxRetries());
        int maxAttempts = maxRetries + 1;
        WorkerDispatchTool.DispatchSession session = bindDispatchSession(notebook, ctx);
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
                    // in_progress / obsolete：不由 Loop 强跑
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
            PlanNotebook notebook, ExecutionStreamContext ctx) {
        String tenantId = ctx != null ? ctx.tenantId() : null;
        List<String> whitelist = toolSetResolver.resolveReactTools(tenantId);
        WorkerDispatchTool.DispatchSession session = new WorkerDispatchTool.DispatchSession(
                notebook,
                whitelist != null ? whitelist : List.of(),
                ctx != null ? ctx.userId() : null,
                tenantId,
                ctx != null ? ctx.assistantMsgId() : null,
                ctx != null ? ctx.conversationId() : null,
                ctx != null ? ctx.assistantMsgId() : null,
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
            // pending：尚未执行；fail：planNext 内 dispatch 失败，仍由 Loop 按 maxRetries 承接
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
