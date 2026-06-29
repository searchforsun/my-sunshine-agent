package com.sunshine.orchestrator.controller;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.orchestrator.exception.OrchestratorErrorCode;
import com.sunshine.orchestrator.controller.stream.ChatResumePreparation;
import com.sunshine.orchestrator.controller.stream.ChatStreamContext;
import com.sunshine.orchestrator.controller.stream.ChatStreamContextFactory;
import com.sunshine.orchestrator.execution.ExecutionDispatcher;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.PlanWorkflowExecutor;
import com.sunshine.orchestrator.execution.ReactExecutor;
import com.sunshine.orchestrator.execution.ReactResumeContextSupport;
import com.sunshine.orchestrator.plan.ExecutionPlanStore;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import com.sunshine.orchestrator.routing.ExecutionPlanParser;
import com.sunshine.orchestrator.routing.ExecutionPlanRouter;
import com.sunshine.orchestrator.routing.policy.RoutingContext;
import com.sunshine.orchestrator.util.StreamErrorMessages;
import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.ProcessingStepMerger;
import com.sunshine.orchestrator.agent.StepEventBridge;
import com.sunshine.orchestrator.client.DesensitizeClient;
import com.sunshine.orchestrator.client.StreamChunkSplitter;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.client.StreamTokenCoalescer;
import com.sunshine.orchestrator.config.ReactiveBlocking;
import com.sunshine.orchestrator.conversation.ConversationService;
import com.sunshine.orchestrator.conversation.GenerationFlushScheduler;
import com.sunshine.orchestrator.conversation.MessageStatus;
import com.sunshine.orchestrator.memory.MemoryLifecycleService;
import com.sunshine.orchestrator.generation.GenerationJob;
import com.sunshine.orchestrator.generation.GenerationJobFactory;
import com.sunshine.orchestrator.generation.DistributedGenerationLock;
import com.sunshine.orchestrator.generation.GenerationProperties;
import com.sunshine.orchestrator.generation.GenerationRegistry;
import com.sunshine.orchestrator.generation.GenerationStatus;
import com.sunshine.orchestrator.generation.GenerationStreamService;
import com.sunshine.orchestrator.generation.StreamEvent;
import com.sunshine.orchestrator.hitl.HitlConfirmationService;
import com.sunshine.orchestrator.model.ChatMessage;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.ProcessingTimelineSupport;
import com.sunshine.orchestrator.rewrite.QueryRewriteTrace;
import com.sunshine.orchestrator.processing.ThinkStepMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ChatController {

    private final ExecutionPlanRouter executionPlanRouter;
    private final ChatStreamContextFactory streamContextFactory;
    private final ExecutionDispatcher executionDispatcher;
    private final ReactExecutor reactExecutor;
    private final PlanWorkflowExecutor planWorkflowExecutor;
    private final ExecutionPlanStore executionPlanStore;
    private final ExecutionPlanParser executionPlanParser;
    private final com.sunshine.orchestrator.execution.SimpleLlmExecutor simpleLlmExecutor;
    private final ConversationService conversationService;
    private final GenerationFlushScheduler flushScheduler;
    private final GenerationProperties generationProperties;
    private final DesensitizeClient desensitizeClient;
    private final MemoryLifecycleService memoryLifecycleService;

    @Autowired(required = false)
    private GenerationJobFactory jobFactory;

    @Autowired(required = false)
    private GenerationRegistry registry;

    @Autowired(required = false)
    private GenerationStreamService streamService;

    @Autowired(required = false)
    private DistributedGenerationLock generationFlushLock;

    @Autowired(required = false)
    private HitlConfirmationService hitlConfirmationService;

    @Value("${agent.generation.flush-interval-ms:500}")
    private long flushIntervalMs;

    @Value("${agent.generation.max-chunk-chars:32}")
    private int maxStreamChunkChars;

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(
            @RequestBody ChatMessage msg,
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {

        validateRequest(msg);
        if (!StringUtils.hasText(msg.getResumeMessageId())) {
            log.info("[Orchestrator] chat pref={} workflowId={} skillId={} conv={}",
                    msg.getExecutionPreference(), msg.getWorkflowId(), msg.getSkillId(), msg.getConversationId());
        }

        if (StringUtils.hasText(msg.getResumeMessageId())) {
            return handleResume(msg, userId, tenantId);
        }
        return handleNewMessage(msg, userId, tenantId);
    }

    private void validateRequest(ChatMessage msg) {
        boolean hasContent = StringUtils.hasText(msg.getContent());
        boolean hasResume = StringUtils.hasText(msg.getResumeMessageId());
        if (hasContent == hasResume) {
            throw new BizException(OrchestratorErrorCode.INVALID_CHAT_REQUEST);
        }
    }

    private Flux<ServerSentEvent<String>> handleNewMessage(
            ChatMessage msg, String userId, String tenantId) {

        return ReactiveBlocking.call(() -> streamContextFactory.prepareNewMessage(msg, userId, tenantId))
                .flatMapMany(ctx -> {
                    AtomicReference<ExecutionMode> executionMode = initialExecutionMode(ctx);
                    Flux<StreamToken> chunkFlux = resolveChunkFlux(ctx, executionMode, false);
                    if (jobFactory != null && streamService != null && registry != null) {
                        return handleNewMessageWithRedis(ctx, executionMode, chunkFlux);
                    }
                    return wrapStream(ctx, chunkFlux, false, executionMode);
                });
    }

    private Flux<ServerSentEvent<String>> handleNewMessageWithRedis(
            ChatStreamContext ctx, AtomicReference<ExecutionMode> executionMode, Flux<StreamToken> chunkFlux) {
        return startRedisGeneration(ctx, executionMode, chunkFlux, false);
    }

    private Flux<ServerSentEvent<String>> startRedisGeneration(
            ChatStreamContext ctx,
            AtomicReference<ExecutionMode> executionMode,
            Flux<StreamToken> chunkFlux,
            boolean resume) {
        QueryRewriteTrace.bind(ctx.assistantMsgId());
        String generationId = streamService.createGeneration(
                ctx.conversationId(), ctx.assistantMsgId(), ctx.userId(), ctx.tenantId(), ctx.intent());

        if (!registry.tryLockMessage(ctx.assistantMsgId(), generationId)) {
            throw new BizException(OrchestratorErrorCode.GENERATION_IN_PROGRESS);
        }
        if (generationFlushLock != null && !generationFlushLock.tryAcquire(generationId)) {
            registry.unlockMessage(ctx.assistantMsgId());
            throw new BizException(OrchestratorErrorCode.GENERATION_IN_PROGRESS);
        }

        return runRedisGeneration(ctx, executionMode, chunkFlux, generationId, resume);
    }

    private Flux<ServerSentEvent<String>> runRedisGeneration(
            ChatStreamContext ctx,
            AtomicReference<ExecutionMode> executionMode,
            Flux<StreamToken> chunkFlux,
            String generationId,
            boolean resume) {
        GenerationJob job = jobFactory.create(
                generationId, ctx.assistantMsgId(), ctx.conversationId(),
                ctx.userId(), ctx.tenantId(), ctx.intent(), ctx.userContent());
        registry.register(job);
        StepEventBridge.bindGenerationFlush(ctx.assistantMsgId(), job::emitStreamToken);

        boolean planWorkflowResume = resume
                && executionPlanStore.findResumableForMessage(ctx.assistantMsgId()).isPresent();
        java.util.List<ProcessingStep> initialSteps = resume
                ? new java.util.ArrayList<>(ProcessingStepMerger.fromJson(ctx.existingStepsJson()))
                : java.util.List.of();
        boolean reactHitlResume = resume
                && ProcessingStepMerger.findReactAwaitingHitlStep(initialSteps) != null;
        // Plan / ReAct-HITL 续跑不拼接暂停前 partial 正文（ReAct-HITL 须先 re-await）
        String initialContent = resume && !planWorkflowResume && !reactHitlResume
                ? ctx.existingContent() : "";
        StringBuilder buffer = new StringBuilder(initialContent != null ? initialContent : "");
        String initialReasoning = resume ? ctx.existingReasoning() : "";
        Consumer<String> flushPartial = content ->
                flushScheduler.flushPartial(ctx.assistantMsgId(), content);
        Runnable onComplete = () -> Mono.fromRunnable(() -> {
                    StepEventBridge.unbindGenerationFlush(ctx.assistantMsgId());
                    QueryRewriteTrace.clear(ctx.assistantMsgId());
                    if (!resume) {
                        maybeUpdateTitle(ctx);
                    }
                    registry.remove(generationId);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
        Consumer<Throwable> onError = error -> {
            StepEventBridge.unbindGenerationFlush(ctx.assistantMsgId());
            QueryRewriteTrace.clear(ctx.assistantMsgId());
            Mono.fromRunnable(() -> registry.remove(generationId))
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe();
            log.error("[Orchestrator] generation 异常 genId={} resume={}", generationId, resume, error);
        };

        job.start(prepareChunkFlux(chunkFlux), buffer, initialReasoning, initialSteps,
                flushPartial, onComplete, onError, executionMode);

        return sseFluxFromRedis(ctx, generationId, job, resume);
    }

    private Flux<ServerSentEvent<String>> sseFluxFromRedis(
            ChatStreamContext ctx, String generationId, GenerationJob job, boolean resume) {

        Flux<ServerSentEvent<String>> meta = Flux.just(
                sse(flushScheduler.metaConversation(ctx.conversationId())),
                sse(flushScheduler.metaMessage(ctx.assistantMsgId(), MessageStatus.STREAMING, resume)),
                sse(flushScheduler.metaGeneration(generationId, ctx.assistantMsgId()))
        );

        List<StreamEvent> existing = streamService.readFrom(
                generationId, 0, generationProperties.maxBufferChunks());
        AtomicLong lastEmittedSeq = new AtomicLong(
                existing.stream().mapToLong(StreamEvent::seq).max().orElse(0));

        Flux<ServerSentEvent<String>> historical = Flux.fromIterable(existing)
                .doOnNext(e -> lastEmittedSeq.updateAndGet(cur -> Math.max(cur, e.seq())))
                .map(e -> sseWithId(String.valueOf(e.seq()), e.text()));

        long subscribeAfter = lastEmittedSeq.get();
        Flux<ServerSentEvent<String>> live = streamService.subscribe(generationId, subscribeAfter)
                .doOnNext(e -> lastEmittedSeq.set(e.seq()))
                .takeUntilOther(
                        Flux.interval(Duration.ofMillis(50))
                                .filter(t -> isCaughtUpAndTerminal(generationId, lastEmittedSeq.get()))
                                .take(1))
                .map(e -> sseWithId(String.valueOf(e.seq()), e.text()));

        Flux<ServerSentEvent<String>> done = Flux.defer(() -> Flux.just(
                sse(flushScheduler.metaMessage(ctx.assistantMsgId(), resolveFinalStatus(generationId), false))));

        return Flux.concat(meta, historical, live, done)
                .doOnSubscribe(s -> job.onSubscriberAttached())
                .doOnCancel(job::onSubscriberGone)
                .doOnComplete(() -> log.info("[Orchestrator] 流式完成 conv={} gen={}",
                        ctx.conversationId(), generationId))
                .doOnError(e -> log.error("[Orchestrator] SSE 异常 genId={}", generationId, e));
    }

    private boolean isCaughtUpAndTerminal(String generationId, long lastEmittedSeq) {
        return streamService.getMeta(generationId)
                .map(meta -> {
                    GenerationStatus status = meta.status();
                    boolean terminal = status == GenerationStatus.COMPLETED
                            || status == GenerationStatus.FAILED
                            || status == GenerationStatus.INTERRUPTED;
                    return terminal && lastEmittedSeq >= meta.lastSeq();
                })
                .orElse(false);
    }

    private String resolveFinalStatus(String generationId) {
        return streamService.getMeta(generationId)
                .map(meta -> switch (meta.status()) {
                    case FAILED -> MessageStatus.FAILED;
                    case INTERRUPTED -> MessageStatus.INTERRUPTED;
                    default -> MessageStatus.COMPLETED;
                })
                .orElse(MessageStatus.COMPLETED);
    }

    private Flux<ServerSentEvent<String>> handleResume(
            ChatMessage msg, String userId, String tenantId) {

        return ReactiveBlocking.call(() -> streamContextFactory.buildResumePreparation(msg, userId, tenantId))
                .flatMapMany(prep -> {
                    if (jobFactory != null && streamService != null && registry != null) {
                        return startResumeWithRedis(prep);
                    }
                    conversationService.commitResumeStart(prep.assistantId(), prep.resumeContent());
                    ChatStreamContext ctx = prep.toStreamContext();
                    AtomicReference<ExecutionMode> executionMode = initialExecutionMode(ctx);
                    Flux<StreamToken> chunkFlux = resolveChunkFlux(ctx, executionMode, true);
                    return wrapStream(ctx, chunkFlux, true, executionMode);
                });
    }

    /** 续跑：先占 message 锁再置 streaming，避免锁冲突后 DB 卡在 streaming 导致 409 */
    private Flux<ServerSentEvent<String>> startResumeWithRedis(ChatResumePreparation prep) {
        String generationId = streamService.createGeneration(
                prep.conversationId(), prep.assistantId(), prep.userId(), prep.tenantId(), prep.intent());
        return Mono.fromCallable(() -> {
                    registry.findByMessageId(prep.assistantId())
                            .ifPresent(job -> registry.cancel(job.getGenerationId()));
                    registry.clearStaleLockIfNoActiveJob(prep.assistantId());
                    if (!registry.tryLockMessage(prep.assistantId(), generationId)) {
                        throw new BizException(OrchestratorErrorCode.GENERATION_IN_PROGRESS);
                    }
                    if (generationFlushLock != null && !generationFlushLock.tryAcquire(generationId)) {
                        registry.unlockMessage(prep.assistantId());
                        throw new BizException(OrchestratorErrorCode.GENERATION_IN_PROGRESS);
                    }
                    conversationService.commitResumeStart(prep.assistantId(), prep.resumeContent());
                    return prep.toStreamContext();
                })
                .flatMapMany(ctx -> {
                    AtomicReference<ExecutionMode> executionMode = initialExecutionMode(ctx);
                    Flux<StreamToken> chunkFlux = resolveChunkFlux(ctx, executionMode, true);
                    return runRedisGeneration(ctx, executionMode, chunkFlux, generationId, true);
                });
    }


    private Flux<ServerSentEvent<String>> wrapStream(
            ChatStreamContext ctx, Flux<StreamToken> chunkFlux, boolean resume,
            AtomicReference<ExecutionMode> executionMode) {

        StringBuilder buffer = new StringBuilder();
        if (resume) {
            boolean planWorkflowResume = executionPlanStore
                    .findResumableForMessage(ctx.assistantMsgId()).isPresent();
            java.util.List<ProcessingStep> steps = ProcessingStepMerger.fromJson(ctx.existingStepsJson());
            boolean reactHitlResume = ProcessingStepMerger.findReactAwaitingHitlStep(steps) != null;
            if (!planWorkflowResume && !reactHitlResume && StringUtils.hasText(ctx.existingContent())) {
                buffer.append(ctx.existingContent());
            }
        }
        StringBuilder reasoningBuffer = new StringBuilder(resume ? ctx.existingReasoning() : "");
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

    private Flux<StreamToken> resolveChunkFlux(
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
            if (resume && plan.mode() == ExecutionMode.REACT && hitlConfirmationService != null) {
                java.util.List<ProcessingStep> existingSteps =
                        ProcessingStepMerger.fromJson(ctx.existingStepsJson());
                ProcessingStep awaiting = ProcessingStepMerger.findReactAwaitingHitlStep(existingSteps);
                if (awaiting != null) {
                    java.util.List<String> resumeInjected =
                            ReactResumeContextSupport.buildInjectedBlocks(existingSteps);
                    return prepareChunkFlux(
                            Mono.fromCallable(() -> hitlConfirmationService.resumeReactAwaiting(
                                            awaiting.id(), ctx.assistantMsgId(), awaiting))
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .flatMapMany(approved -> approved
                                            ? reactExecutor.executeWithInjected(execCtx, resumeInjected)
                                            : Flux.empty()));
                }
            }
            // 仅 simple-llm 续写 partial；ReAct/workflow 须走原执行器（含 HITL）
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
                            if (org.springframework.util.StringUtils.hasText(skillId)) {
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

    private AtomicReference<ExecutionMode> initialExecutionMode(ChatStreamContext ctx) {
        AtomicReference<ExecutionMode> mode = new AtomicReference<>(ExecutionMode.REACT);
        if (ctx.intent() != null) {
            mode.set(executionPlanParser.parseStoredIntent(ctx.intent()).mode());
        }
        return mode;
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
                plan);
    }

    private static List<StreamToken> drainStepTokens(List<ProcessingStep> stepEmissions) {
        List<StreamToken> tokens = stepEmissions.stream().map(StreamToken::step).toList();
        stepEmissions.clear();
        return tokens;
    }

    private Flux<StreamToken> prepareChunkFlux(Flux<StreamToken> raw) {
        return StreamChunkSplitter.split(StreamTokenCoalescer.coalesce(raw), maxStreamChunkChars);
    }

    private void maybeUpdateTitle(ChatStreamContext ctx) {
        if (!ctx.autoTitle() || ctx.conversationTitle() == null || !"新对话".equals(ctx.conversationTitle())) {
            return;
        }
        String title = ctx.userContent().length() > 28
                ? ctx.userContent().substring(0, 28)
                : ctx.userContent();
        Mono.fromRunnable(() -> conversationService.updateTitle(
                        ctx.conversationId(), ctx.userId(), ctx.tenantId(), title))
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

    private ServerSentEvent<String> sseWithId(String id, String data) {
        return ServerSentEvent.<String>builder()
                .id(id)
                .data(data)
                .build();
    }
}
