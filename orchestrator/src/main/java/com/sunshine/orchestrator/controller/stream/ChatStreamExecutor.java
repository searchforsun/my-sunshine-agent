package com.sunshine.orchestrator.controller.stream;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.ProcessingStepMerger;
import com.sunshine.orchestrator.agent.ProcessingStepLifecycleOps;
import com.sunshine.orchestrator.agent.ProcessingStepSerde;
import com.sunshine.orchestrator.client.DesensitizeClient;
import com.sunshine.orchestrator.client.StreamChunkSplitter;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.client.StreamTokenCoalescer;
import com.sunshine.orchestrator.conversation.ConversationService;
import com.sunshine.orchestrator.conversation.ConversationTitleService;
import com.sunshine.orchestrator.conversation.GenerationFlushScheduler;
import com.sunshine.orchestrator.conversation.MessageStatus;
import com.sunshine.orchestrator.execution.ExecutionDispatcher;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.WorkflowResumeService;
import com.sunshine.orchestrator.context.ContextLifecycle;
import com.sunshine.orchestrator.plan.ExecutionPlanStore;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.ProcessingTimelineSupport;
import com.sunshine.orchestrator.processing.ThinkStepMapper;
import com.sunshine.orchestrator.processing.TimelineStepId;
import com.sunshine.orchestrator.rewrite.QueryRewriteTrace;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import com.sunshine.orchestrator.routing.ExecutionPlanParser;
import com.sunshine.orchestrator.routing.ExecutionPlanRouter;
import com.sunshine.orchestrator.routing.policy.RoutingContext;
import com.sunshine.orchestrator.util.StreamErrorMessages;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
/** 直连 SSE 流组装（无 Redis GenerationJob 缓冲） */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatStreamExecutor {

    private final ExecutionPlanStore executionPlanStore;
    private final WorkflowResumeService workflowResumeService;
    private final ExecutionPlanParser executionPlanParser;
    private final ExecutionDispatcher executionDispatcher;
    private final ExecutionPlanRouter executionPlanRouter;
    private final ConversationService conversationService;
    private final ConversationTitleService titleService;
    private final GenerationFlushScheduler flushScheduler;
    private final DesensitizeClient desensitizeClient;
    private final ContextLifecycle contextLifecycle;

    @Value("${agent.generation.flush-interval-ms:500}")
    private long flushIntervalMs;

    @Value("${agent.generation.max-chunk-chars:32}")
    private int maxStreamChunkChars;

    public Flux<ServerSentEvent<String>> wrapStream(
            ChatStreamContext ctx, Flux<StreamToken> chunkFlux, boolean resume,
            AtomicReference<ExecutionMode> executionMode) {

        StringBuilder buffer = new StringBuilder();
        if (resume) {
            boolean planRunResume = executionPlanStore
                    .findResumableForMessage(ctx.assistantMsgId()).isPresent();
            boolean reactRestartResume = !planRunResume && isReactStoredIntent(ctx.intent());
            if (!planRunResume && !reactRestartResume && StringUtils.hasText(ctx.existingContent())) {
                buffer.append(ctx.existingContent());
            }
        }
        boolean planRunResumeOnResume = resume
                && executionPlanStore.findResumableForMessage(ctx.assistantMsgId()).isPresent();
        boolean reactRestartOnResume = resume && !planRunResumeOnResume && isReactStoredIntent(ctx.intent());
        StringBuilder reasoningBuffer = new StringBuilder(
                resume && !planRunResumeOnResume && !reactRestartOnResume
                        ? ctx.existingReasoning() : "");
        java.util.List<ProcessingStep> stepsBuffer = new java.util.ArrayList<>(
                ProcessingStepSerde.fromJson(ctx.existingStepsJson()));
        if (reactRestartOnResume) {
            // 与 ChatController resume 一致：截断到最后一个完整 think，丢弃未终态 tool/rag/tasks
            truncateAfterLastDoneThink(stepsBuffer);
            // 截断后若最后一个 think 仍是 done（中断在其流式中途、半截被误标 done），
            // 该 think 不在 checkpoint message 历史里、将被重生成，重置为 paused 让前端清空旧内容
            resetTrailingDoneThinkToPaused(stepsBuffer);
        }
        QueryRewriteTrace.bind(ctx.assistantMsgId());
        ThinkStepMapper thinkMapper = new ThinkStepMapper(stepsBuffer, ctx.userContent(), executionMode);
        var appender = flushScheduler.createChunkAppender(buffer, ctx.assistantMsgId(), flushIntervalMs);

        Flux<ServerSentEvent<String>> meta = Flux.just(
                sse(flushScheduler.metaConversation(ctx.conversationId())),
                sse(flushScheduler.metaMessage(ctx.assistantMsgId(), MessageStatus.STREAMING, resume))
        );

        // 续跑直连 SSE，须在 boundedElastic 执行 DAG/Agent，避免 reactor-http 线程 block()
        Flux<ServerSentEvent<String>> chunks = chunkFlux
                .subscribeOn(Schedulers.boundedElastic())
                .concatMap(token -> Flux.fromIterable(thinkMapper.map(token)))
                .concatWith(Flux.defer(() -> Flux.fromIterable(thinkMapper.finish())))
                .doOnNext(token -> {
                    if (token.isStep()) {
                        ProcessingStepMerger.upsert(stepsBuffer, token.step());
                        return;
                    }
                    if (token.isStepDelta()) {
                        ProcessingStepMerger.applyDelta(
                                stepsBuffer, token.stepId(), token.channel(), token.text());
                        if ("reasoning".equals(token.channel())) {
                            reasoningBuffer.append(token.text());
                        }
                        return;
                    }
                    if (token.isContent()) {
                        appender.accept(desensitizeClient.scrub(token.text()));
                    } else if (token.isReasoning()) {
                        reasoningBuffer.append(token.text());
                    }
                })
                .map(this::tokenToSse);

        Flux<ServerSentEvent<String>> done = Flux.defer(() -> {
            Mono.fromRunnable(() -> {
                        flushScheduler.commitFinal(
                                ctx.assistantMsgId(),
                                buffer.toString(),
                                reasoningBuffer.toString(),
                                MessageStatus.COMPLETED,
                                ProcessingStepSerde.toJson(stepsBuffer));
                        contextLifecycle.onTurnCompleted(
                                ctx.assistantMsgId(), ctx.userId(), ctx.tenantId(), MessageStatus.COMPLETED);
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe();
            return Flux.just(sse(flushScheduler.metaMessage(
                    ctx.assistantMsgId(), MessageStatus.COMPLETED, resume)));
        });

        return Flux.merge(Flux.concat(meta, chunks, done), titleService.titleEventSse(ctx))
                .onErrorResume(e -> {
                    String errMsg = StreamErrorMessages.resolve(e);
                    if (buffer.length() > 0) {
                        buffer.append("\n\n").append(errMsg);
                    } else {
                        buffer.append(errMsg);
                    }
                    // 异常中断：running 步（含 rag/tool/think 等）标 paused 再落库，避免残留 running
                    ProcessingStepLifecycleOps.pauseRunningReactSteps(stepsBuffer);
                    Mono.fromRunnable(() ->
                                    flushScheduler.commitFinal(
                                            ctx.assistantMsgId(),
                                            buffer.toString(),
                                            reasoningBuffer.toString(),
                                            MessageStatus.FAILED,
                                            ProcessingStepSerde.toJson(stepsBuffer)))
                            .subscribeOn(Schedulers.boundedElastic())
                            .subscribe();
                    return Flux.just(
                            sse(flushScheduler.metaError(errMsg)),
                            sse(flushScheduler.metaMessage(
                                    ctx.assistantMsgId(), MessageStatus.FAILED, resume)));
                })
                .doOnCancel(() -> Mono.fromRunnable(() -> {
                                // 用户中断：running 步（含 rag/tool/think 等）标 paused 再落库，避免残留 running
                                ProcessingStepLifecycleOps.pauseRunningReactSteps(stepsBuffer);
                                flushScheduler.commitFinal(
                                        ctx.assistantMsgId(),
                                        buffer.toString(),
                                        reasoningBuffer.toString(),
                                        MessageStatus.INTERRUPTED,
                                        ProcessingStepSerde.toJson(stepsBuffer));
                            })
                        .subscribeOn(Schedulers.boundedElastic())
                        .subscribe())
                .doOnComplete(() -> log.info("[Orchestrator] 流式完成 conv={}", ctx.conversationId()))
                .doOnError(e -> log.error("[Orchestrator] 异常", e))
                .doFinally(sig -> QueryRewriteTrace.clear(ctx.assistantMsgId()));
    }

    public Flux<StreamToken> resolveChunkFlux(
            ChatStreamContext ctx, AtomicReference<ExecutionMode> executionMode, boolean resume) {
        var resumablePlan = executionPlanStore.findResumableForMessage(ctx.assistantMsgId());
        if (resumablePlan.isPresent()) {
            ExecutionPlan plan = new ExecutionPlan(ExecutionMode.PRO, null, Map.of(), "resume");
            executionMode.set(ExecutionMode.PRO);
            ExecutionStreamContext execCtx = toExecutionContext(ctx, plan)
                    .withPersistedPlanId(resumablePlan.get().getId());
            return prepareChunkFlux(workflowResumeService.resumePaused(execCtx, resumablePlan.get()));
        }
        if (ctx.intent() != null) {
            ExecutionPlan plan = executionPlanParser.parseStoredIntent(ctx.intent());
            executionMode.set(plan.mode());
            ExecutionStreamContext execCtx = toExecutionContext(ctx, plan);
            // ReAct 暂停续跑：仅保留 intent，从规划推理重新开始（见 ChatStreamContextFactory）
            return prepareChunkFlux(executionDispatcher.execute(execCtx));
        }

        ProcessingTimelineSession session = ProcessingTimelineSupport.newSession();
        session.bindUserQuery(ctx.userContent());
        session.bindTraceMessageId(ctx.assistantMsgId());
        List<ProcessingStep> stepEmissions = new ArrayList<>();
        session.onStepChanged(stepEmissions::add);
        session.pending(TimelineStepId.INTENT.id(), TimelineStepId.INTENT.phase());
        session.start(TimelineStepId.INTENT.id(), TimelineStepId.INTENT.phase());
        List<StreamToken> intentStartTokens = drainStepTokens(stepEmissions);

        return prepareChunkFlux(Flux.concat(
                Flux.fromIterable(intentStartTokens),
                executionPlanRouter.route(new RoutingContext(
                        ctx.userContent(),
                        ctx.assistantMsgId(),
                        ctx.executionPreference(),
                        ctx.forcedWorkflowId(),
                        ctx.clientSkillId(),
                        ctx.memory(),
                        null,
                        StringUtils.hasText(ctx.conversationKind()) ? ctx.conversationKind() : "chat"))
                        .flatMapMany(plan -> {
                            executionMode.set(plan.mode());
                            Mono<Void> savePlan = Mono.fromRunnable(() ->
                                            conversationService.updateMessageExecutionPlan(
                                                    ctx.assistantMsgId(), plan))
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .then();
                            session.completeIntent(plan);
                            List<StreamToken> intentDoneTokens = drainStepTokens(stepEmissions);
                            String skillId = plan.params() != null
                                    ? plan.params().get(com.sunshine.orchestrator.skill.SkillBindingOutcome.PARAM_SKILL)
                                    : null;
                            if (StringUtils.hasText(skillId)) {
                                session.completeSkillLoad(skillId.strip());
                            }
                            List<StreamToken> skillDoneTokens = drainStepTokens(stepEmissions);
                            return savePlan.thenMany(Flux.concat(
                                    Flux.fromIterable(intentDoneTokens),
                                    Flux.fromIterable(skillDoneTokens),
                                    executionDispatcher.execute(toExecutionContext(ctx, plan))
                            ));
                        })
                        .onErrorResume(e -> {
                            session.fail(TimelineStepId.INTENT.id(), StreamErrorMessages.resolve(e));
                            List<StreamToken> failTokens = drainStepTokens(stepEmissions);
                            return Flux.concat(Flux.fromIterable(failTokens), Flux.error(e));
                        })
        ));
    }

    public AtomicReference<ExecutionMode> initialExecutionMode(ChatStreamContext ctx) {
        AtomicReference<ExecutionMode> mode = new AtomicReference<>(ExecutionMode.FAST);
        if (ctx.intent() != null) {
            mode.set(executionPlanParser.parseStoredIntent(ctx.intent()).mode());
        }
        return mode;
    }

    public Flux<StreamToken> prepareChunkFlux(Flux<StreamToken> raw) {
        return StreamChunkSplitter.split(StreamTokenCoalescer.coalesce(raw), maxStreamChunkChars);
    }

    public boolean isReactStoredIntent(String intent) {
        if (intent == null || intent.isBlank()) {
            return true;
        }
        ExecutionMode mode = executionPlanParser.parseStoredIntent(intent).mode();
        return mode == ExecutionMode.FAST;
    }

    private static ExecutionStreamContext toExecutionContext(ChatStreamContext ctx, ExecutionPlan plan) {
        return new ExecutionStreamContext(
                ctx.conversationId(),
                ctx.assistantMsgId(),
                ctx.userContent(),
                ctx.memory(),
                ctx.existingContent(),
                ctx.existingReasoning(),
                ctx.userId(),
                ctx.tenantId(),
                ctx.kbId(),
                plan,
                null,
                null,
                null,
                false,
                ctx.reactRestart(),
                ctx.existingStepsJson(),
                ctx.personalRules(),
                ctx.conversationKind(),
                ctx.modelOverride());
    }

    private static List<StreamToken> drainStepTokens(List<ProcessingStep> stepEmissions) {
        List<StreamToken> tokens = stepEmissions.stream().map(StreamToken::step).toList();
        stepEmissions.clear();
        return tokens;
    }

    public void maybeUpdateTitle(ChatStreamContext ctx) {
        if (!ctx.autoTitle()) {
            return;
        }
        Mono.fromRunnable(() -> conversationService.autoTitleIfDefault(
                        ctx.conversationId(), ctx.userId(), ctx.tenantId(), ctx.userContent()))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    /** 断点续传截断：仅保留到「最后一个完整 think」，丢弃半截 think 及其后 tool/rag/tasks（见 ThinkStepIds） */
    private static void truncateAfterLastDoneThink(java.util.List<ProcessingStep> steps) {
        com.sunshine.orchestrator.processing.ThinkStepIds.truncateToLastCompleteThink(steps);
    }

    /** 截断后若最后一个步是 done think（流式中途被误标 done），重置为 paused 并清 reasoning，续跑重推时前端从头渲染 */
    private static void resetTrailingDoneThinkToPaused(java.util.List<ProcessingStep> steps) {
        for (int i = steps.size() - 1; i >= 0; i--) {
            ProcessingStep s = steps.get(i);
            if (!com.sunshine.orchestrator.processing.ThinkStepIds.isThinkStep(s.id())) {
                continue;
            }
            if ("done".equals(s.lifecycle())) {
                com.sunshine.orchestrator.processing.StepSummary summary = s.summary();
                steps.set(i, new ProcessingStep(
                        s.id(), s.phase(), "paused",
                        new com.sunshine.orchestrator.processing.StepSummary(
                                summary != null ? summary.before() : null, "已暂停", "已暂停"),
                        s.startedAt(), null, null,
                        s.detail(), null, s.output(), s.result(),
                        System.currentTimeMillis(), s.label(), s.metadata(),
                        s.contentBlocks(), s.subSteps(), s.stepSummary()));
            }
            return;
        }
    }

    private ServerSentEvent<String> tokenToSse(StreamToken token) {
        if (token.isSandboxSession()) {
            return sse(flushScheduler.metaSandboxSession(token.text(), token.channel(), token.stepId()));
        }
        if (token.isStep()) {
            return sse(flushScheduler.metaStep(token.step()));
        }
        if (token.isStepDelta()) {
            return sse(flushScheduler.metaStepDelta(
                    token.stepId(), token.channel(), token.text()));
        }
        if (token.isContentStart()) {
            return sse(flushScheduler.metaContentStart(
                    token.segmentId(), token.afterStepId(), token.scopeNodeStepId()));
        }
        if (token.isContentEnd()) {
            return sse(flushScheduler.metaContentEnd(token.segmentId(), token.scopeNodeStepId()));
        }
        if (token.isContent()) {
            if (token.segmentId() != null) {
                return sse(flushScheduler.metaContentInSegment(
                        token.segmentId(), token.text(), token.scopeNodeStepId()));
            }
            return sse(flushScheduler.metaContent(token.text(), token.afterStepId()));
        }
        return sse(flushScheduler.metaReasoning(token.text()));
    }

    private ServerSentEvent<String> sse(String data) {
        return ServerSentEvent.<String>builder()
                .id(UUID.randomUUID().toString().substring(0, 8))
                .data(data)
                .build();
    }
}
