package com.sunshine.orchestrator.plan.harness;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.agent.runtime.AgentRuntime;
import com.sunshine.orchestrator.catalog.ToolSetResolver;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.context.ContextAssembler;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

/**
 * Planner-Executor 结构化入口：planNext / selfAssess / synthesizeAnswer。
 * <p>
 * 动作协议（架构决议）：planNext/selfAssess 通过 {@link PlannerActionTool} 工具调用表达动作，
 * 引擎按 {@code DispatchSession.ActionSignals} 判定是否收到；不再解析模型正文 JSON。
 * Round 边界：本类只更新 {@code taskQueue} / {@code goalCompletion} / {@code nextDirection}，
 * <b>不</b>追加 {@code RoundRecord}。Worker 执行回写由 {@link WorkerDispatchTool} 独占。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HarnessPlanner {

    public static final String CATALOG_ID = "planner.harness";
    private static final String HINT_PLAN = "请通过 plan_submit 工具提交本轮调度单元；若未调用该工具，本轮规划视为失败。";
    private static final String HINT_ASSESS = "请通过 self_assess 工具汇报本轮进度与决策；若未调用该工具，本轮自判视为失败。";
    private static final String HINT_ANSWER = "请综合回答用户，停止调用工具。";

    private final AgentRuntime agentRuntime;
    private final ContextAssembler contextAssembler;
    private final AgentExecutionProperties executionProperties;
    private final ToolSetResolver toolSetResolver;

    public void planNext(PlanNotebook notebook, ExecutionStreamContext ctx) {
        int maxAttempts = plannerMaxAttempts();
        IllegalStateException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            PlannerInvocation invocation = invokePlanner(notebook, ctx, HINT_PLAN);
            if (invocation.session() != null && invocation.session().signals().planReceived().get()) {
                return;
            }
            last = new IllegalStateException(
                    "Planner 未调用 plan_submit 提交调度单元（attempt=" + attempt + "）: " + preview(invocation.text()));
            log.warn("[HarnessPlanner] planNext 未收到 plan_submit attempt={}/{} preview={}",
                    attempt, maxAttempts, preview(invocation.text()));
        }
        throw new IllegalStateException("HarnessPlanner planNext 未收到 plan_submit，已重试 " + maxAttempts, last);
    }

    public void selfAssess(PlanNotebook notebook, ExecutionStreamContext ctx) {
        int maxAttempts = plannerMaxAttempts();
        IllegalStateException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            PlannerInvocation invocation = invokePlanner(notebook, ctx, HINT_ASSESS);
            if (invocation.session() != null && invocation.session().signals().assessReceived().get()) {
                return;
            }
            last = new IllegalStateException(
                    "Planner 未调用 self_assess 汇报决策（attempt=" + attempt + "）: " + preview(invocation.text()));
            log.warn("[HarnessPlanner] selfAssess 未收到 self_assess attempt={}/{} preview={}",
                    attempt, maxAttempts, preview(invocation.text()));
        }
        throw new IllegalStateException("HarnessPlanner selfAssess 未收到 self_assess，已重试 " + maxAttempts, last);
    }

    public Flux<StreamToken> synthesizeAnswer(PlanNotebook notebook, ExecutionStreamContext ctx) {
        AgentRunRequest request = buildRequest(notebook, ctx, HINT_ANSWER);
        return Flux.defer(() -> {
            WorkerDispatchTool.DispatchSession session = bindDispatchSession(notebook, ctx, request.runId());
            StreamToken start = StreamToken.step(ProcessingStep.running(
                    "planner-answer", "answer", "综合回答"));
            StreamToken end = StreamToken.step(ProcessingStep.done(
                    "planner-answer", "answer", "综合回答", "完成"));
            return Flux.just(start)
                    .concatWith(agentRuntime.run(request))
                    .concatWith(Flux.just(end))
                    .doFinally(sig -> WorkerDispatchTool.clearSession(session));
        });
    }

    /**
     * 同步调用 Planner ReAct，收集正文并回传本次 run 的 DispatchSession（供动作信号判定）。
     * RoundRecord 仍只由 WorkerDispatchTool 写入。
     */
    private PlannerInvocation invokePlanner(PlanNotebook notebook, ExecutionStreamContext ctx, String phaseHint) {
        AgentRunRequest request = buildRequest(notebook, ctx, phaseHint);
        WorkerDispatchTool.DispatchSession session = bindDispatchSession(notebook, ctx, request.runId());
        try {
            StringBuilder answer = new StringBuilder();
            long timeoutMs = plannerTimeoutMs();
            agentRuntime.run(request)
                    .doOnNext(token -> WorkerDispatchTool.appendAnswerContent(answer, token))
                    .blockLast(Duration.ofMillis(timeoutMs));
            return new PlannerInvocation(answer.toString(), session);
        } finally {
            WorkerDispatchTool.clearSession(session);
        }
    }

    private record PlannerInvocation(String text, WorkerDispatchTool.DispatchSession session) {
    }

    private AgentRunRequest buildRequest(PlanNotebook notebook, ExecutionStreamContext ctx, String phaseHint) {
        AssembledContext memory = resolveMemory(ctx);
        int nearKeep = executionProperties.getHarness().getNotebook().getCompression().getNearKeepRounds();
        List<String> injected = List.of(notebook.renderForPlanner(nearKeep));
        String query = buildQuery(ctx, phaseHint);
        return AgentRunRequest.planner(
                memory,
                query,
                injected,
                ctx.userId(),
                ctx.tenantId(),
                ctx.assistantMsgId(),
                ctx.conversationId(),
                0)
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
                        ctx.userContent()));
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
            PlanNotebook notebook, ExecutionStreamContext ctx, String parentRunId) {
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
                WorkerDispatchTool.DispatchSession.ActionSignals.fresh());
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

    static void replaceTaskQueue(PlanNotebook notebook, List<TaskItem> tasks) {
        notebook.getTaskQueue().clear();
        if (tasks != null) {
            notebook.getTaskQueue().addAll(tasks);
        }
    }

    private static String buildQuery(ExecutionStreamContext ctx, String phaseHint) {
        String user = ctx != null && StringUtils.hasText(ctx.userContent()) ? ctx.userContent().strip() : "";
        if (!StringUtils.hasText(user)) {
            return phaseHint;
        }
        return user + "\n\n" + phaseHint;
    }

    private static boolean isBare(AssembledContext memory) {
        return memory.nearTurns().isEmpty()
                && memory.midTurns().isEmpty()
                && !StringUtils.hasText(memory.l2SystemBlock())
                && !StringUtils.hasText(memory.farSummaryBlock())
                && !StringUtils.hasText(memory.l3MaterialBlock())
                && !StringUtils.hasText(memory.projectGuideBlock());
    }

    private int plannerMaxAttempts() {
        int n = executionProperties.getHarness().getPlanner().getMaxAttempts();
        return n > 0 ? n : 3;
    }

    private long plannerTimeoutMs() {
        long ms = executionProperties.getHarness().getPlanner().getTimeoutMs();
        return ms > 0 ? ms : 300_000L;
    }

    private static String preview(String text) {
        if (text == null) {
            return "";
        }
        String s = text.strip();
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }
}
