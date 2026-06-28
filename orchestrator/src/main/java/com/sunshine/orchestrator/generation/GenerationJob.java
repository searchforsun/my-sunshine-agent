package com.sunshine.orchestrator.generation;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.ProcessingStepMerger;
import com.sunshine.orchestrator.processing.ContentBlockAccumulator;
import com.sunshine.orchestrator.processing.ThinkStepMapper;
import com.sunshine.orchestrator.client.StreamChunkSplitter;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.util.StreamErrorMessages;
import com.sunshine.orchestrator.config.AgentPauseProperties;
import com.sunshine.orchestrator.conversation.GenerationFlushScheduler;
import com.sunshine.orchestrator.conversation.MessageStatus;
import com.sunshine.orchestrator.execution.WorkflowPauseService;
import com.sunshine.orchestrator.plan.ExecutionPlanStore;
import com.sunshine.orchestrator.plan.PausePhase;
import com.sunshine.orchestrator.plan.PendingInteraction;
import com.sunshine.orchestrator.plan.WorkflowCheckpoint;
import com.sunshine.orchestrator.execution.WorkflowContextCodec;
import com.sunshine.orchestrator.memory.MemoryLifecycleService;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
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

    private volatile Disposable llmSubscription;
    private volatile Disposable orphanTimer;
    private volatile StringBuilder mysqlBufferRef;
    private volatile StringBuilder reasoningBufferRef;
    private final java.util.List<ProcessingStep> stepsBuffer = new java.util.ArrayList<>();
    private final ContentBlockAccumulator contentBlockAccumulator = new ContentBlockAccumulator();
    private ThinkStepMapper thinkMapper;

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
        streamService.updateStatus(generationId, GenerationStatus.RUNNING);
        Consumer<String> guardedFlush = guardFlush(flushPartial);

        AtomicLong lastFlush = new AtomicLong(0);

        llmSubscription = llmFlux
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        chunk -> onChunk(chunk, mysqlBuffer, guardedFlush, lastFlush),
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
            // 须在 dispose 之前落库：dispose 触发 WorkflowExecutor.doFinally → clearRun，会丢失 wfCtx
            persistWorkflowPauseIfNeeded();
            emitFinishSteps(true);
            emitPausedWorkflowSteps();
            disposeLlmSubscription();
            streamService.updateStatus(generationId, GenerationStatus.INTERRUPTED);
            persistFinal(MessageStatus.INTERRUPTED, () -> { });
        });
    }

    private void persistWorkflowPauseIfNeeded() {
        executionPlanStore.findByMessageId(messageId)
                .filter(executionPlanStore::isPausableForWorkflowStop)
                .ifPresent(entity -> {
                    WorkflowCheckpoint checkpoint = buildWorkflowPauseCheckpoint(entity);
                    executionPlanStore.markPaused(entity.getId(), checkpoint);
                });
    }

    private WorkflowCheckpoint buildWorkflowPauseCheckpoint(
            com.sunshine.orchestrator.plan.ExecutionPlanEntity entity) {
        PendingInteraction pending = pauseProperties.isResumeInteractionEnabled()
                ? ProcessingStepMerger.findPendingInteraction(stepsBuffer) : null;
        if (pending != null) {
            String ctxJson = resolveWfCtxJson(pending.nodeId());
            return new WorkflowCheckpoint(pending.nodeId(), ctxJson, PausePhase.EXECUTING, pending);
        }
        String nodeId = workflowPauseService.getCurrentNodeId(messageId);
        if (!org.springframework.util.StringUtils.hasText(nodeId)) {
            nodeId = ProcessingStepMerger.findLastRunningWorkflowNodeId(stepsBuffer);
        }
        if (org.springframework.util.StringUtils.hasText(nodeId)) {
            return new WorkflowCheckpoint(nodeId, resolveWfCtxJson(nodeId), PausePhase.EXECUTING, null);
        }
        PausePhase pausePhase = PausePhase.PLANNING;
        String resumeNodeId = executionPlanStore.inferPlanningResumeNodeId(entity);
        return new WorkflowCheckpoint(resumeNodeId, "{}", pausePhase, null);
    }

    private String resolveWfCtxJson(String nodeId) {
        String ctxJson = workflowPauseService.getCommittedContextJson(messageId);
        if (!WorkflowContextCodec.hasNodes(ctxJson)) {
            ctxJson = executionPlanStore.findByMessageId(messageId)
                    .filter(e -> org.springframework.util.StringUtils.hasText(e.getPauseCheckpoint()))
                    .map(executionPlanStore::loadCheckpoint)
                    .filter(cp -> WorkflowContextCodec.hasNodes(cp.wfCtxJson()))
                    .map(WorkflowCheckpoint::wfCtxJson)
                    .orElse(ctxJson);
        }
        if (!WorkflowContextCodec.hasNodes(ctxJson)) {
            log.warn("[GenerationJob] 暂停检查点 wfCtx 为空 msg={} node={}，续跑可能丢失上游",
                    messageId, nodeId);
        }
        return ctxJson;
    }

    private void emitPausedWorkflowSteps() {
        String nodeId = workflowPauseService.getCurrentNodeId(messageId);
        PendingInteraction pending = ProcessingStepMerger.findPendingInteraction(stepsBuffer);
        String skipNodeId = pending != null ? pending.nodeId() : null;
        if (ProcessingStepMerger.hasRunningWorkflowNode(stepsBuffer)
                || org.springframework.util.StringUtils.hasText(nodeId)) {
            ProcessingStepMerger.pauseRunningWorkflowNodes(stepsBuffer, nodeId, skipNodeId);
        }
        ProcessingStepMerger.pauseRunningReactSteps(stepsBuffer);
        StringBuilder mysqlBuffer = mysqlBufferRef;
        AtomicLong lastFlush = new AtomicLong(0);
        for (ProcessingStep step : stepsBuffer) {
            if ("paused".equals(step.lifecycle())) {
                emitMappedChunk(StreamToken.step(step), mysqlBuffer != null ? mysqlBuffer : new StringBuilder(),
                        directPartialFlush(), lastFlush);
            }
        }
    }

    /** HITL 等旁路事件 — 写入 Redis 流，不进入消息正文缓冲 */
    public void emitOutbound(String wireJson) {
        if (wireJson == null || wireJson.isBlank() || finished.get()) {
            return;
        }
        long nextSeq = seq.incrementAndGet();
        streamService.appendChunk(generationId, nextSeq, wireJson);
    }

    /** Hook 队列中的 step / step_delta 即时刷入 Redis（HITL 阻塞前须先下发 think / tool 步骤） */
    public void emitStreamToken(StreamToken token) {
        if (token == null || finished.get()) {
            return;
        }
        if (token.isStep()) {
            thinkMapper.syncExternalStep(token.step());
        }
        emitMappedChunk(token, new StringBuilder(), s -> { }, new java.util.concurrent.atomic.AtomicLong(0));
    }

    private void onChunk(StreamToken token, StringBuilder mysqlBuffer,
            Consumer<String> flushPartial, AtomicLong lastFlush) {
        if (token.isStep() || token.isStepDelta() || token.isContentStart() || token.isContentEnd()) {
            if (token.isStep()) {
                thinkMapper.syncExternalStep(token.step());
            }
            emitMappedChunk(token, mysqlBuffer, flushPartial, lastFlush);
            return;
        }
        if (token.isContent() && token.segmentId() != null) {
            emitMappedChunk(token, mysqlBuffer, flushPartial, lastFlush);
            return;
        }
        for (StreamToken mapped : thinkMapper.map(token)) {
            emitMappedChunk(mapped, mysqlBuffer, flushPartial, lastFlush);
        }
    }

    private void emitMappedChunk(StreamToken token, StringBuilder mysqlBuffer,
            Consumer<String> flushPartial, AtomicLong lastFlush) {
        int maxChars = properties.maxChunkChars();
        if (maxChars <= 0) {
            emitSingleMappedChunk(token, mysqlBuffer, flushPartial, lastFlush);
            return;
        }
        for (StreamToken piece : StreamChunkSplitter.splitToken(token, maxChars)) {
            emitSingleMappedChunk(piece, mysqlBuffer, flushPartial, lastFlush);
        }
    }

    /** 单帧写入 Redis / MySQL 缓冲（Hook 与主 Flux 共用，大段在此层之前已切分） */
    private void emitSingleMappedChunk(StreamToken token, StringBuilder mysqlBuffer,
            Consumer<String> flushPartial, AtomicLong lastFlush) {
        long nextSeq = seq.incrementAndGet();
        if (token.isStep()) {
            ProcessingStepMerger.upsert(stepsBuffer, token.step());
            streamService.appendChunk(generationId, nextSeq, flushScheduler.metaStep(token.step()));
            return;
        }
        if (token.isStepDelta()) {
            ProcessingStepMerger.applyDelta(
                    stepsBuffer, token.stepId(), token.channel(), token.text());
            streamService.appendChunk(generationId, nextSeq, flushScheduler.metaStepDelta(
                    token.stepId(), token.channel(), token.text()));
            if ("reasoning".equals(token.channel()) && reasoningBufferRef != null
                    && (token.stepId() == null || !token.stepId().startsWith("node-"))) {
                reasoningBufferRef.append(token.text());
            }
            return;
        }
        if (token.isContentStart()) {
            contentBlockAccumulator.onContentStart(token);
            streamService.appendChunk(generationId, nextSeq,
                    flushScheduler.metaContentStart(
                            token.segmentId(), token.afterStepId(), token.scopeNodeStepId()));
            return;
        }
        if (token.isContentEnd()) {
            contentBlockAccumulator.onContentEnd(token);
            streamService.appendChunk(generationId, nextSeq,
                    flushScheduler.metaContentEnd(token.segmentId(), token.scopeNodeStepId()));
            return;
        }
        String wire = token.isContent()
                ? (token.segmentId() != null
                ? flushScheduler.metaContentInSegment(
                        token.segmentId(), token.text(), token.scopeNodeStepId())
                : flushScheduler.metaContent(token.text(), token.afterStepId()))
                : flushScheduler.metaReasoning(token.text());
        streamService.appendChunk(generationId, nextSeq, wire);
        if (token.isReasoning()) {
            if (reasoningBufferRef != null) {
                reasoningBufferRef.append(token.text());
            }
            return;
        }
        if (token.isContent() && token.segmentId() != null) {
            contentBlockAccumulator.onContent(token);
        }
        // 子 Agent 分段正文仅落 steps.contentBlocks，勿混入 message.content（避免污染 answer 节点）
        if (org.springframework.util.StringUtils.hasText(token.scopeNodeStepId())) {
            long now = System.currentTimeMillis();
            if (now - lastFlush.get() >= properties.flushIntervalMs()) {
                lastFlush.set(now);
                flushPartial.accept(mysqlBuffer.toString());
            }
            return;
        }
        if (token.isContent() && token.text() != null) {
            mysqlBuffer.append(token.text());
        }

        long now = System.currentTimeMillis();
        if (now - lastFlush.get() >= properties.flushIntervalMs()) {
            lastFlush.set(now);
            flushPartial.accept(mysqlBuffer.toString());
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
        cancelOrphanTimer();
        disposeLlmSubscription();
        emitFinishSteps(true);
        String errMsg = StreamErrorMessages.resolve(error);
        if (errMsg != null && !errMsg.isBlank()) {
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
        streamService.updateStatus(generationId, GenerationStatus.FAILED);
        persistFinal(MessageStatus.FAILED, () -> onError.accept(error));
    }

    /** commitFinal 含脱敏 block 调用，须在 boundedElastic 执行，避免 reactor 线程 IllegalStateException */
    private void persistFinal(String status, Runnable afterPersist) {
        String content = bufferContent();
        String reasoning = bufferReasoning();
        contentBlockAccumulator.mergeIntoSteps(stepsBuffer);
        String steps = stepsJson();
        String contentBlocks = contentBlockAccumulator.messageBlocksJson();
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
        return ProcessingStepMerger.toPersistJson(stepsBuffer);
    }

    private void emitFinishSteps() {
        emitFinishSteps(false);
    }

    private void emitFinishSteps(boolean streamFailed) {
        if (thinkMapper == null) {
            return;
        }
        StringBuilder mysqlBuffer = mysqlBufferRef;
        AtomicLong lastFlush = new AtomicLong(0);
        for (StreamToken token : thinkMapper.finish(streamFailed)) {
            emitMappedChunk(token, mysqlBuffer != null ? mysqlBuffer : new StringBuilder(),
                    directPartialFlush(), lastFlush);
        }
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
