package com.sunshine.orchestrator.controller.stream;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.ProcessingStepMerger;
import com.sunshine.orchestrator.client.DesensitizeClient;
import com.sunshine.orchestrator.client.StreamChunkSplitter;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.client.StreamTokenCoalescer;
import com.sunshine.orchestrator.conversation.ConversationService;
import com.sunshine.orchestrator.conversation.GenerationFlushScheduler;
import com.sunshine.orchestrator.conversation.MessageStatus;
import com.sunshine.orchestrator.execution.ExecutionDispatcher;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.PlanWorkflowExecutor;
import com.sunshine.orchestrator.memory.MemoryLifecycleService;
import com.sunshine.orchestrator.plan.ExecutionPlanStore;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.ProcessingTimelineSupport;
import com.sunshine.orchestrator.processing.ThinkStepMapper;
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
import java.util.function.Consumer;

/** 直连 SSE 流组装（无 Redis GenerationJob 缓冲） */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatStreamExecutor {

    private final ExecutionPlanStore executionPlanStore;
    private final PlanWorkflowExecutor planWorkflowExecutor;
    private final ExecutionPlanParser executionPlanParser;
    private final com.sunshine.orchestrator.execution.SimpleLlmExecutor simpleLlmExecutor;
    private final ExecutionDispatcher executionDispatcher;
    private final ExecutionPlanRouter executionPlanRouter;
    private final ConversationService conversationService;
    private final GenerationFlushScheduler flushScheduler;
    private final DesensitizeClient desensitizeClient;
    private final MemoryLifecycleService memoryLifecycleService;

    @Value("${agent.generation.flush-interval-ms:500}")
    private long flushIntervalMs;

    @Value("${agent.generation.max-chunk-chars:32}")
    private int maxStreamChunkChars;

    public Flux<ServerSentEvent<String>> wrapStream(
            ChatStreamContext ctx, Flux<StreamToken> chunkFlux, boolean resume,
            AtomicReference<ExecutionMode> executionMode) {

        StringBuilder buffer = new StringBuilder();
        if (resume) {
            boolean planWorkflowResume = executionPlanStore
                    .findResumableForMessage(ctx.assistantMsgId()).isPresent();
            boolean reactRestartResume = !planWorkflowResume && isReactStoredIntent(ctx.intent());
            if (!planWorkflowResume && !reactRestartResume && StringUtils.hasText(ctx.existingContent())) {
                buffer.append(ctx.existingContent());
            }
        }
        boolean planWorkflowResumeOnResume = resume
                && executionPlanStore.findResumableForMessage(ctx.assistantMsgId()).isPresent();
        boolean reactRestartOnResume = resume && !planWorkflowResumeOnResume && isReactStoredIntent(ctx.intent());
        StringBuilder reasoningBuffer = new StringBuilder(
                resume && !planWorkflowResumeOnResume && !reactRestartOnResume
                        ? ctx.existingReasoning() : "");
        java.util.List<ProcessingStep> stepsBuffer = new java.util.ArrayList<>(
                ProcessingStepMerger.fromJson(ctx.existingStepsJson()));
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
                                ProcessingStepMerger.toJson(stepsBuffer));
                        maybeUpdateTitle(ctx);
                        memoryLifecycleService.onAssistantCompleted(
                                ctx.assistantMsgId(), ctx.userId(), ctx.tenantId(), MessageStatus.COMPLETED);
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe();
            return Flux.just(sse(flushScheduler.metaMessage(
                    ctx.assistantMsgId(), MessageStatus.COMPLETED, resume)));
        });

        return Flux.concat(meta, chunks, done)
                .onErrorResume(e -> {
                    String errMsg = StreamErrorMessages.resolve(e);
                    if (buffer.length() > 0) {
                        buffer.append("\n\n").append(errMsg);
                    } else {
                        buffer.append(errMsg);
                    }
                    Mono.fromRunnable(() ->
                                    flushScheduler.commitFinal(
                                            ctx.assistantMsgId(),
                                            buffer.toString(),
                                            reasoningBuffer.toString(),
                                            MessageStatus.FAILED,
                                            ProcessingStepMerger.toJson(stepsBuffer)))
                            .subscribeOn(Schedulers.boundedElastic())
                            .subscribe();
                    return Flux.just(
                            sse(flushScheduler.metaError(errMsg)),
                            sse(flushScheduler.metaMessage(
                                    ctx.assistantMsgId(), MessageStatus.FAILED, resume)));
                })
                .doOnCancel(() -> Mono.fromRunnable(() ->
                                flushScheduler.commitFinal(
                                        ctx.assistantMsgId(),
                                        buffer.toString(),
                                        reasoningBuffer.toString(),
                                        MessageStatus.INTERRUPTED,
                                        ProcessingStepMerger.toJson(stepsBuffer)))
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
            ExecutionPlan plan = new ExecutionPlan(ExecutionMode.PLAN_WORKFLOW, null, Map.of(), "resume");
            executionMode.set(ExecutionMode.PLAN_WORKFLOW);
            ExecutionStreamContext execCtx = toExecutionContext(ctx, plan)
                    .withPersistedPlanId(resumablePlan.get().getId());
            return prepareChunkFlux(planWorkflowExecutor.resumePaused(execCtx, resumablePlan.get()));
        }
        if (ctx.intent() != null) {
            ExecutionPlan plan = executionPlanParser.parseStoredIntent(ctx.intent());
            executionMode.set(plan.mode());
            ExecutionStreamContext execCtx = toExecutionContext(ctx, plan);
            // ReAct 暂停续跑：仅保留 intent，从规划推理重新开始（见 ChatStreamContextFactory）
            if (plan.mode() == ExecutionMode.SIMPLE_LLM && StringUtils.hasText(ctx.existingContent())) {
                return prepareChunkFlux(simpleLlmExecutor.execute(execCtx));
            }
            return prepareChunkFlux(executionDispatcher.execute(execCtx));
        }

        ProcessingTimelineSession session = ProcessingTimelineSupport.newSession();
        session.bindUserQuery(ctx.userContent());
        session.bindTraceMessageId(ctx.assistantMsgId());
        List<ProcessingStep> stepEmissions = new ArrayList<>();
        session.onStepChanged(stepEmissions::add);
        session.pending("intent", "intent");
        session.start("intent", "intent");
        List<StreamToken> intentStartTokens = drainStepTokens(stepEmissions);

        return prepareChunkFlux(Flux.concat(
                Flux.fromIterable(intentStartTokens),
                executionPlanRouter.route(new RoutingContext(
                        ctx.userContent(),
                        ctx.assistantMsgId(),
                        ctx.executionPreference(),
                        ctx.forcedWorkflowId(),
                        ctx.clientSkillId(),
                        ctx.memory()))
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
        ));
    }

    public AtomicReference<ExecutionMode> initialExecutionMode(ChatStreamContext ctx) {
        AtomicReference<ExecutionMode> mode = new AtomicReference<>(ExecutionMode.REACT);
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
        return executionPlanParser.parseStoredIntent(intent).mode() == ExecutionMode.REACT;
    }

    private static ExecutionStreamContext toExecutionContext(ChatStreamContext ctx, ExecutionPlan plan) {
        return new ExecutionStreamContext(
                ctx.conversationId(),
                ctx.assistantMsgId(),
                ctx.userContent(),
                ctx.memory(),
                ctx.existingContent(),
                ctx.existingReasoning(),
                ctx.intent(),
                ctx.userId(),
                ctx.tenantId(),
                plan,
                null,
                null,
                null,
                false,
                ctx.reactRestart());
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

    private ServerSentEvent<String> tokenToSse(StreamToken token) {
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
