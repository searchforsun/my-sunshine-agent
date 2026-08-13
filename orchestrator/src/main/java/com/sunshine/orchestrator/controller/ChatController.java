package com.sunshine.orchestrator.controller;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.orchestrator.exception.OrchestratorErrorCode;
import com.sunshine.orchestrator.controller.stream.ChatResumePreparation;
import com.sunshine.orchestrator.controller.stream.ChatStreamContext;
import com.sunshine.orchestrator.controller.stream.ChatStreamContextFactory;
import com.sunshine.orchestrator.controller.stream.ChatStreamExecutor;
import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.ProcessingStepSerde;
import com.sunshine.orchestrator.agent.StepEventBridge;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.ReactiveBlocking;
import com.sunshine.orchestrator.conversation.ConversationService;
import com.sunshine.orchestrator.conversation.ConversationTitleService;
import com.sunshine.orchestrator.conversation.GenerationFlushScheduler;
import com.sunshine.orchestrator.conversation.MessageStatus;
import com.sunshine.orchestrator.generation.GenerationJob;
import com.sunshine.orchestrator.generation.GenerationJobFactory;
import com.sunshine.orchestrator.generation.DistributedGenerationLock;
import com.sunshine.orchestrator.generation.GenerationProperties;
import com.sunshine.orchestrator.generation.GenerationRegistry;
import com.sunshine.orchestrator.generation.GenerationStreamService;
import com.sunshine.orchestrator.generation.StreamEvent;
import com.sunshine.orchestrator.hitl.HitlConfirmationService;
import com.sunshine.orchestrator.model.ChatMessage;
import com.sunshine.orchestrator.plan.ExecutionPlanStore;
import com.sunshine.orchestrator.rewrite.QueryRewriteTrace;
import com.sunshine.orchestrator.routing.ExecutionMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ChatController {

    private final ChatStreamContextFactory streamContextFactory;
    private final ChatStreamExecutor streamExecutor;
    private final ExecutionPlanStore executionPlanStore;
    private final ConversationService conversationService;
    private final ConversationTitleService titleService;
    private final GenerationFlushScheduler flushScheduler;
    private final GenerationProperties generationProperties;
    private final com.sunshine.orchestrator.agent.ReactCheckpointService checkpointService;

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

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(
            @RequestBody ChatMessage msg,
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {

        validateRequest(msg);
        if (!StringUtils.hasText(msg.getResumeMessageId())) {
            log.info("[Orchestrator] chat mode={} workflowId={} skillId={} conv={}",
                    msg.resolveExecutionModeWire(), msg.getWorkflowId(), msg.getSkillId(), msg.getConversationId());
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
                    StepEventBridge.bindWriteHitlMode(
                            ctx.assistantMsgId(),
                            com.sunshine.orchestrator.sandbox.SandboxWriteHitlMode.from(msg.getWriteHitlMode()));
                    AtomicReference<ExecutionMode> executionMode = streamExecutor.initialExecutionMode(ctx);
                    Flux<StreamToken> chunkFlux = streamExecutor.resolveChunkFlux(ctx, executionMode, false);
                    if (jobFactory != null && streamService != null && registry != null) {
                        return startRedisGeneration(ctx, executionMode, chunkFlux, false);
                    }
                    return streamExecutor.wrapStream(ctx, chunkFlux, false, executionMode);
                });
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
        long streamEpoch = StepEventBridge.currentStreamEpoch(ctx.assistantMsgId());
        job.bindStreamEpoch(streamEpoch);
        StepEventBridge.bindGenerationFlush(ctx.assistantMsgId(), streamEpoch, job::emitStreamToken);

        boolean planRunResume = resume
                && executionPlanStore.findResumableForMessage(ctx.assistantMsgId()).isPresent();
        boolean reactRestartResume = resume && !planRunResume && streamExecutor.isReactStoredIntent(ctx.intent());
        boolean hasNativeCheckpoint = reactRestartResume
                && checkpointService.hasCheckpoint(ctx.userId(), ctx.assistantMsgId());
        log.info("[ChatController] resume msg={} reactRestart={} hasNativeCheckpoint={} initialSteps={}",
                ctx.assistantMsgId(), reactRestartResume, hasNativeCheckpoint,
                resume ? ProcessingStepSerde.fromJson(ctx.existingStepsJson()).size() : 0);
        java.util.List<ProcessingStep> initialSteps = resume
                ? new java.util.ArrayList<>(ProcessingStepSerde.fromJson(ctx.existingStepsJson()))
                : java.util.List.of();
        if (reactRestartResume) {
            // 无论有无 native checkpoint：截断半截步，保留 tasks/decision；无 checkpoint 靠注入块续进度
            truncateAfterLastDoneThink(initialSteps);
        }
        String initialContent = resume && !planRunResume && !reactRestartResume
                ? ctx.existingContent() : "";
        StringBuilder buffer = new StringBuilder(initialContent != null ? initialContent : "");
        String initialReasoning = resume && !planRunResume && !reactRestartResume
                ? ctx.existingReasoning() : "";
        Consumer<String> flushPartial = content ->
                flushScheduler.flushPartial(ctx.assistantMsgId(), content);
        Runnable onComplete = () -> Mono.fromRunnable(() -> {
                    StepEventBridge.unbindGenerationFlush(ctx.assistantMsgId());
                    StepEventBridge.clearResumeContentBlocks(ctx.assistantMsgId());
                    QueryRewriteTrace.clear(ctx.assistantMsgId());
                    if (!resume) {
                        streamExecutor.maybeUpdateTitle(ctx);
                    }
                    registry.remove(generationId);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
        Consumer<Throwable> onError = error -> {
            StepEventBridge.unbindGenerationFlush(ctx.assistantMsgId());
            StepEventBridge.clearResumeContentBlocks(ctx.assistantMsgId());
            QueryRewriteTrace.clear(ctx.assistantMsgId());
            Mono.fromRunnable(() -> registry.remove(generationId))
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe();
            log.error("[Orchestrator] generation 异常 genId={} resume={}", generationId, resume, error);
        };

        job.start(streamExecutor.prepareChunkFlux(chunkFlux), buffer, initialReasoning, initialSteps,
                flushPartial, onComplete, onError, executionMode);
        if (reactRestartResume) {
            job.seedResumeContentBlocks(StepEventBridge.peekResumeContentBlocks(ctx.assistantMsgId()));
        }

        return sseFluxFromRedis(ctx, generationId, job, resume);
    }

    /** 断点续传截断：仅保留到「最后一个完整 think」，丢弃半截 think 及其后 tool/rag/tasks（见 ThinkStepIds） */
    private static void truncateAfterLastDoneThink(java.util.List<ProcessingStep> steps) {
        com.sunshine.orchestrator.processing.ThinkStepIds.truncateToLastCompleteThink(steps);
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
        Flux<ServerSentEvent<String>> live = streamService.subscribeToEnd(generationId, subscribeAfter)
                .map(e -> sseWithId(String.valueOf(e.seq()), e.text()));

        Flux<ServerSentEvent<String>> done = Flux.defer(() -> Flux.just(
                sse(flushScheduler.metaMessage(ctx.assistantMsgId(), resolveFinalStatus(generationId), false))));

        return Flux.merge(Flux.concat(meta, historical, live, done), titleService.titleEventSse(ctx))
                .doOnSubscribe(s -> job.onSubscriberAttached())
                .doOnCancel(job::onSubscriberGone)
                .doOnComplete(() -> log.info("[Orchestrator] 流式完成 conv={} gen={}",
                        ctx.conversationId(), generationId))
                .doOnError(e -> log.error("[Orchestrator] SSE 异常 genId={}", generationId, e));
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
                        return startResumeWithRedis(prep, msg.getWriteHitlMode());
                    }
                    conversationService.commitResumeStart(
                            prep.assistantId(),
                            prep.resumeContent(),
                            prep.resumeReasoning(),
                            prep.stepsJson(),
                            prep.contentBlocksJson());
                    if (prep.reactRestart()) {
                        StepEventBridge.clearForReactRestart(prep.assistantId());
                        if (hitlConfirmationService != null) {
                            hitlConfirmationService.invalidateForMessageRestart(prep.assistantId());
                        }
                    }
                    ChatStreamContext ctx = prep.toStreamContext();
                    StepEventBridge.bindWriteHitlMode(
                            ctx.assistantMsgId(),
                            com.sunshine.orchestrator.sandbox.SandboxWriteHitlMode.from(msg.getWriteHitlMode()));
                    AtomicReference<ExecutionMode> executionMode = streamExecutor.initialExecutionMode(ctx);
                    Flux<StreamToken> chunkFlux = streamExecutor.resolveChunkFlux(ctx, executionMode, true);
                    return streamExecutor.wrapStream(ctx, chunkFlux, true, executionMode);
                });
    }

    /** 续跑：先占 message 锁再置 streaming，避免锁冲突后 DB 卡在 streaming 导致 409 */
    private Flux<ServerSentEvent<String>> startResumeWithRedis(ChatResumePreparation prep, String writeHitlMode) {
        String generationId = streamService.createGeneration(
                prep.conversationId(), prep.assistantId(), prep.userId(), prep.tenantId(), prep.intent());
        return Mono.fromCallable(() -> {
                    String msgId = prep.assistantId();
                    StepEventBridge.unbindGenerationFlush(msgId);
                    registry.findByMessageId(msgId)
                            .ifPresent(job -> registry.cancel(job.getGenerationId()));
                    registry.releaseBlockingWaitsForMessage(msgId);
                    if (prep.reactRestart()) {
                        StepEventBridge.bumpStreamEpoch(msgId);
                        StepEventBridge.clearForReactRestart(msgId);
                        if (hitlConfirmationService != null) {
                            hitlConfirmationService.invalidateForMessageRestart(msgId);
                        }
                    }
                    registry.clearStaleLockIfNoActiveJob(prep.assistantId());
                    if (!registry.tryLockMessage(prep.assistantId(), generationId)) {
                        throw new BizException(OrchestratorErrorCode.GENERATION_IN_PROGRESS);
                    }
                    if (generationFlushLock != null && !generationFlushLock.tryAcquire(generationId)) {
                        registry.unlockMessage(prep.assistantId());
                        throw new BizException(OrchestratorErrorCode.GENERATION_IN_PROGRESS);
                    }
                    String contentBlocksForResume = prep.contentBlocksJson();
                    if (prep.reactRestart()) {
                        java.util.List<ProcessingStep> truncated = new java.util.ArrayList<>(
                                ProcessingStepSerde.fromJson(prep.stepsJson()));
                        truncateAfterLastDoneThink(truncated);
                        contentBlocksForResume = com.sunshine.orchestrator.processing.ContentBlocksJson
                                .pruneForReactResume(prep.contentBlocksJson(), truncated);
                    }
                    conversationService.commitResumeStart(
                            prep.assistantId(),
                            prep.resumeContent(),
                            prep.resumeReasoning(),
                            prep.stepsJson(),
                            contentBlocksForResume);
                    // clearForReactRestart 之后再挂载：抬高 segment seq + 落库合并种子
                    if (prep.reactRestart()) {
                        StepEventBridge.prepareResumeContentBlocks(
                                prep.assistantId(), contentBlocksForResume);
                    }
                    return prep.toStreamContext();
                })
                .flatMapMany(ctx -> {
                    StepEventBridge.bindWriteHitlMode(
                            ctx.assistantMsgId(),
                            com.sunshine.orchestrator.sandbox.SandboxWriteHitlMode.from(writeHitlMode));
                    AtomicReference<ExecutionMode> executionMode = streamExecutor.initialExecutionMode(ctx);
                    Flux<StreamToken> chunkFlux = streamExecutor.resolveChunkFlux(ctx, executionMode, true);
                    return runRedisGeneration(ctx, executionMode, chunkFlux, generationId, true);
                });
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
