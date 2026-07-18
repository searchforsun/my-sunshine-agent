package com.sunshine.orchestrator.generation;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.ProcessingStepLifecycleOps;
import com.sunshine.orchestrator.agent.ProcessingStepSerde;
import com.sunshine.orchestrator.agent.StepEventBridge;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.util.StreamErrorMessages;
import com.sunshine.orchestrator.config.AgentPauseProperties;
import com.sunshine.orchestrator.conversation.GenerationFlushScheduler;
import com.sunshine.orchestrator.conversation.MessageStatus;
import com.sunshine.orchestrator.execution.WorkflowPauseService;
import com.sunshine.orchestrator.hitl.HitlWaitInterruptedException;
import com.sunshine.orchestrator.plan.ExecutionPlanStore;
import com.sunshine.orchestrator.plan.PendingInteraction;
import com.sunshine.orchestrator.plan.WorkflowCheckpoint;
import com.sunshine.orchestrator.memory.MemoryLifecycleService;
import com.sunshine.orchestrator.processing.ContentBlockAccumulator;
import com.sunshine.orchestrator.processing.ThinkStepMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Slf4j
@Getter
public class GenerationJob {

    private final String generationId;
    private final String messageId;
    private final String conversationId;
    private final String userId;
    private final String tenantId;
    private final String intent;
    private final String userQuery;
    private final GenerationStreamService streamService;
    private final GenerationProperties properties;
    private final GenerationFlushScheduler flushScheduler;
    private final MemoryLifecycleService memoryLifecycleService;
    private final WorkflowPauseService workflowPauseService;
    private final ExecutionPlanStore executionPlanStore;
    private final AgentPauseProperties pauseProperties;
    private final DistributedGenerationLock flushLock;

    private final AtomicLong seq = new AtomicLong(0);
    private final AtomicBoolean finished = new AtomicBoolean(false);
    /**
     * 串行化 {@code seq++ + XADD}。工具/spawn 可并行执行，但显式 Stream ID（{@code seq-0}）
     * 要求写入单调递增；并发 XADD 会触发 Redis「equal or smaller than the target stream top item」。
     */
    private final Object streamAppendLock = new Object();

    private volatile Disposable llmSubscription;
    private volatile Disposable orphanTimer;
    private volatile StringBuilder mysqlBufferRef;
    private volatile StringBuilder reasoningBufferRef;
    private final java.util.List<ProcessingStep> stepsBuffer = new java.util.ArrayList<>();
    private final ContentBlockAccumulator contentBlockAccumulator = new ContentBlockAccumulator();
    private ThinkStepMapper thinkMapper;
    private GenerationJobChunkEmitter chunkEmitter;
    private volatile long boundStreamEpoch = Long.MIN_VALUE;

    GenerationJob(String generationId, String messageId, String conversationId,
            String userId, String tenantId, String intent, String userQuery,
            GenerationStreamService streamService,
            GenerationProperties properties,
            GenerationFlushScheduler flushScheduler,
            MemoryLifecycleService memoryLifecycleService,
            WorkflowPauseService workflowPauseService,
            ExecutionPlanStore executionPlanStore,
            AgentPauseProperties pauseProperties,
            DistributedGenerationLock flushLock) {
        this.generationId = generationId;
        this.messageId = messageId;
        this.conversationId = conversationId;
        this.userId = userId;
        this.tenantId = tenantId;
        this.intent = intent;
        this.userQuery = userQuery;
        this.streamService = streamService;
        this.properties = properties;
        this.flushScheduler = flushScheduler;
        this.memoryLifecycleService = memoryLifecycleService;
        this.workflowPauseService = workflowPauseService;
        this.executionPlanStore = executionPlanStore;
        this.pauseProperties = pauseProperties != null ? pauseProperties : new AgentPauseProperties();
        this.flushLock = flushLock;
    }

    /** 与 StepEventBridge.STREAM_EPOCH 对齐，防止已取消 job 的 onChunk 写入新 Redis 流 */
    public void bindStreamEpoch(long epoch) {
        this.boundStreamEpoch = epoch;
    }

    public void start(Flux<StreamToken> llmFlux, StringBuilder mysqlBuffer,
            Consumer<String> flushPartial, Runnable onComplete, Consumer<Throwable> onError) {
        start(llmFlux, mysqlBuffer, "", java.util.List.of(), flushPartial, onComplete, onError,
                new AtomicReference<>(ExecutionMode.REACT));
    }

    public void start(Flux<StreamToken> llmFlux, StringBuilder mysqlBuffer,
            Consumer<String> flushPartial, Runnable onComplete, Consumer<Throwable> onError,
            AtomicReference<ExecutionMode> executionMode) {
        start(llmFlux, mysqlBuffer, "", java.util.List.of(), flushPartial, onComplete, onError, executionMode);
    }

    /** 续跑：预填正文 / reasoning / steps，与 wrapStream 对齐 */
    public void start(Flux<StreamToken> llmFlux, StringBuilder mysqlBuffer, String initialReasoning,
            java.util.List<ProcessingStep> initialSteps,
            Consumer<String> flushPartial, Runnable onComplete, Consumer<Throwable> onError,
            AtomicReference<ExecutionMode> executionMode) {
        this.mysqlBufferRef = mysqlBuffer;
        this.reasoningBufferRef = new StringBuilder(initialReasoning != null ? initialReasoning : "");
        if (initialSteps != null && !initialSteps.isEmpty()) {
            stepsBuffer.clear();
            stepsBuffer.addAll(initialSteps);
        }
        this.thinkMapper = new ThinkStepMapper(stepsBuffer, userQuery, executionMode);
        this.chunkEmitter = newChunkEmitter();
        streamService.updateStatus(generationId, GenerationStatus.RUNNING);
        Consumer<String> guardedFlush = guardFlush(flushPartial);
        AtomicLong lastFlush = new AtomicLong(0);
        llmSubscription = llmFlux
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        chunk -> chunkEmitter.onChunk(chunk, mysqlBuffer, guardedFlush, lastFlush),
                        error -> finishOnce(() -> handleError(error, onError)),
                        () -> finishOnce(() -> handleComplete(onComplete))
                );
    }

    public void onSubscriberGone() {
        cancelOrphanTimer();
        orphanTimer = Mono.delay(Duration.ofSeconds(properties.orphanTimeoutSec()))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(v -> {
                    if (!finished.get()) {
                        log.info("[GenerationJob] orphan-timeout fired genId={}", generationId);
                        cancel();
                    }
                });
    }

    public void onSubscriberAttached() {
        cancelOrphanTimer();
    }

    public void cancel() {
        workflowPauseService.requestPause(messageId);
        finishOnce(() -> {
            cancelOrphanTimer();
            persistWorkflowPauseIfNeeded();
            emitFinishSteps(true);
            emitPausedWorkflowSteps();
            disposeLlmSubscription();
            streamService.updateStatus(generationId, GenerationStatus.INTERRUPTED);
            persistFinal(MessageStatus.INTERRUPTED, () -> { });
        });
    }

    /** HITL 等旁路事件 — 写入 Redis 流，不进入消息正文缓冲 */
    public void emitOutbound(String wireJson) {
        if (wireJson == null || wireJson.isBlank() || finished.get() || !isStreamEpochValid()) {
            return;
        }
        synchronized (streamAppendLock) {
            long nextSeq = seq.incrementAndGet();
            streamService.appendChunk(generationId, nextSeq, wireJson);
        }
    }

    /** Hook 队列中的 step / step_delta / 分段 content 即时刷入 Redis（HITL 阻塞前须先下发 think / tool 步骤） */
    public void emitStreamToken(StreamToken token) {
        if (token == null || finished.get() || !isStreamEpochValid()) {
            return;
        }
        StringBuilder buf = mysqlBufferRef != null ? mysqlBufferRef : new StringBuilder();
        ensureChunkEmitter().emitStreamToken(token, buf, directPartialFlush());
    }

    private GenerationJobChunkEmitter ensureChunkEmitter() {
        if (chunkEmitter == null) {
            if (thinkMapper == null) {
                thinkMapper = new ThinkStepMapper(stepsBuffer, userQuery,
                        new AtomicReference<>(ExecutionMode.REACT));
            }
            chunkEmitter = newChunkEmitter();
        }
        return chunkEmitter;
    }

    private void persistWorkflowPauseIfNeeded() {
        executionPlanStore.findByMessageId(messageId)
                .filter(executionPlanStore::isPausableForWorkflowStop)
                .ifPresent(entity -> {
                    WorkflowCheckpoint checkpoint = GenerationJobCheckpointSupport.buildPauseCheckpoint(
                            messageId, stepsBuffer, workflowPauseService, executionPlanStore, pauseProperties, entity);
                    executionPlanStore.markPaused(entity.getId(), checkpoint);
                });
    }

    private void emitPausedWorkflowSteps() {
        String nodeId = workflowPauseService.getCurrentNodeId(messageId);
        PendingInteraction pending = ProcessingStepLifecycleOps.findPendingInteraction(stepsBuffer);
        String skipNodeId = pending != null ? pending.nodeId() : null;
        if (ProcessingStepLifecycleOps.hasRunningWorkflowNode(stepsBuffer)
                || StringUtils.hasText(nodeId)) {
            ProcessingStepLifecycleOps.pauseRunningWorkflowNodes(stepsBuffer, nodeId, skipNodeId);
        }
        ProcessingStepLifecycleOps.pauseRunningReactSteps(stepsBuffer);
        ProcessingStepLifecycleOps.pauseRunningExpertSteps(stepsBuffer);
        StringBuilder mysqlBuffer = mysqlBufferRef;
        Consumer<String> flushPartial = directPartialFlush();
        if (chunkEmitter != null) {
            for (ProcessingStep step : stepsBuffer) {
                if ("paused".equals(step.lifecycle())) {
                    chunkEmitter.emitPausedStep(StreamToken.step(step), mysqlBuffer, flushPartial);
                }
            }
        }
    }

    private void handleComplete(Runnable onComplete) {
        cancelOrphanTimer();
        disposeLlmSubscription();
        emitFinishSteps();
        streamService.updateStatus(generationId, GenerationStatus.COMPLETED);
        persistFinal(MessageStatus.COMPLETED, () -> {
            refreshMemoryAfterComplete();
            onComplete.run();
        });
    }

    private void refreshMemoryAfterComplete() {
        if (memoryLifecycleService == null) {
            return;
        }
        try {
            memoryLifecycleService.onAssistantCompleted(
                    messageId, userId, tenantId, MessageStatus.COMPLETED);
        } catch (Exception e) {
            log.warn("[GenerationJob] STM 刷新失败 msg={}: {}", messageId, e.getMessage());
        }
    }

    private void handleError(Throwable error, Consumer<Throwable> onError) {
        if (finished.get()) {
            return;
        }
        if (error instanceof HitlWaitInterruptedException
                || (error.getCause() instanceof HitlWaitInterruptedException)) {
            cancelOrphanTimer();
            disposeLlmSubscription();
            emitFinishSteps(true);
            emitPausedWorkflowSteps();
            streamService.updateStatus(generationId, GenerationStatus.INTERRUPTED);
            persistFinal(MessageStatus.INTERRUPTED, () -> onError.accept(error));
            return;
        }
        cancelOrphanTimer();
        disposeLlmSubscription();
        emitFinishSteps(true);
        String errMsg = StreamErrorMessages.resolve(error);
        if (errMsg != null && !errMsg.isBlank()) {
            synchronized (streamAppendLock) {
                long nextSeq = seq.incrementAndGet();
                streamService.appendChunk(generationId, nextSeq, flushScheduler.metaError(errMsg));
                StringBuilder buf = mysqlBufferRef;
                if (buf != null) {
                    if (buf.length() > 0) {
                        buf.append("\n\n");
                    }
                    buf.append(errMsg);
                }
            }
        }
        streamService.updateStatus(generationId, GenerationStatus.FAILED);
        persistFinal(MessageStatus.FAILED, () -> onError.accept(error));
    }

    /** commitFinal 含脱敏 block 调用，须在 boundedElastic 执行，避免 reactor 线程 IllegalStateException */
    private void persistFinal(String status, Runnable afterPersist) {
        String buffered = bufferContent();
        String reasoning = bufferReasoning();
        contentBlockAccumulator.mergeIntoSteps(stepsBuffer);
        String steps = stepsJson();
        String contentBlocks = contentBlockAccumulator.messageBlocksJson();
        // ReAct 旁路 emitStreamToken 曾漏写 mysqlBuffer：用 content_blocks 回填，保证 STM SSOT
        final String content;
        if (!StringUtils.hasText(buffered)) {
            String fromBlocks = contentBlockAccumulator.messageBlocksPlainText();
            content = StringUtils.hasText(fromBlocks) ? fromBlocks : buffered;
        } else {
            content = buffered;
        }
        Mono.fromRunnable(() -> {
                    try {
                        if (flushLock == null || flushLock.isHeldByThisInstance(generationId)) {
                            flushScheduler.commitFinal(messageId, content, reasoning, status, steps, contentBlocks);
                        } else {
                            log.warn("[GenerationJob] 终态落库时 flush 锁已丢失，仍强制 commitFinal genId={} msg={}",
                                    generationId, messageId);
                            flushScheduler.commitFinal(messageId, content, reasoning, status, steps, contentBlocks);
                        }
                        afterPersist.run();
                    } finally {
                        if (flushLock != null) {
                            flushLock.release(generationId);
                        }
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        null,
                        e -> log.error("[GenerationJob] 落库失败 msg={} status={}: {}",
                                messageId, status, e.getMessage()));
    }

    private void finishOnce(Runnable action) {
        if (finished.compareAndSet(false, true)) {
            action.run();
        }
    }

    private void cancelOrphanTimer() {
        Disposable timer = orphanTimer;
        orphanTimer = null;
        if (timer != null && !timer.isDisposed()) {
            timer.dispose();
        }
    }

    private void disposeLlmSubscription() {
        Disposable sub = llmSubscription;
        if (sub != null && !sub.isDisposed()) {
            sub.dispose();
        }
    }

    private String bufferContent() {
        StringBuilder buffer = mysqlBufferRef;
        return buffer != null ? buffer.toString() : "";
    }

    private String bufferReasoning() {
        StringBuilder buffer = reasoningBufferRef;
        return buffer != null ? buffer.toString() : "";
    }

    private String stepsJson() {
        return ProcessingStepSerde.toPersistJson(stepsBuffer);
    }

    private void emitFinishSteps() {
        emitFinishSteps(false);
    }

    private void emitFinishSteps(boolean streamFailed) {
        if (chunkEmitter == null) {
            return;
        }
        StringBuilder mysqlBuffer = mysqlBufferRef;
        chunkEmitter.emitFinishSteps(streamFailed, mysqlBuffer, directPartialFlush());
    }

    private GenerationJobChunkEmitter newChunkEmitter() {
        return new GenerationJobChunkEmitter(
                generationId,
                messageId,
                seq,
                streamAppendLock,
                finished,
                boundStreamEpoch,
                stepsBuffer,
                thinkMapper,
                contentBlockAccumulator,
                reasoningBufferRef,
                streamService,
                flushScheduler,
                properties);
    }

    private boolean isStreamEpochValid() {
        return boundStreamEpoch >= 0
                && StepEventBridge.isStreamEpochValid(messageId, boundStreamEpoch);
    }

    private Consumer<String> guardFlush(Consumer<String> flushPartial) {
        if (flushLock == null) {
            return flushPartial;
        }
        return content -> {
            if (flushLock.renewIfHeld(generationId)) {
                flushPartial.accept(content);
            }
        };
    }

    /** pause/finish 路径直接写 MySQL partial，仍须持锁 */
    private Consumer<String> directPartialFlush() {
        return content -> {
            if (flushLock == null || flushLock.renewIfHeld(generationId)) {
                flushScheduler.flushPartial(messageId, content);
            }
        };
    }
}
