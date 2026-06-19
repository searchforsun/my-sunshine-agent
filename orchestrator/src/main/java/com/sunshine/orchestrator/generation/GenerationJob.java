package com.sunshine.orchestrator.generation;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.ProcessingStepMerger;
import com.sunshine.orchestrator.processing.ThinkStepMapper;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.conversation.GenerationFlushScheduler;
import com.sunshine.orchestrator.conversation.MessageStatus;
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

    private final AtomicLong seq = new AtomicLong(0);
    private final AtomicBoolean finished = new AtomicBoolean(false);

    private volatile Disposable llmSubscription;
    private volatile Disposable orphanTimer;
    private volatile StringBuilder mysqlBufferRef;
    private volatile StringBuilder reasoningBufferRef;
    private final java.util.List<ProcessingStep> stepsBuffer = new java.util.ArrayList<>();
    private ThinkStepMapper thinkMapper;

    GenerationJob(String generationId, String messageId, String conversationId,
            String userId, String tenantId, String intent, String userQuery,
            GenerationStreamService streamService,
            GenerationProperties properties,
            GenerationFlushScheduler flushScheduler,
            MemoryLifecycleService memoryLifecycleService) {
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
    }

    public void start(Flux<StreamToken> llmFlux, StringBuilder mysqlBuffer,
            Consumer<String> flushPartial, Runnable onComplete, Consumer<Throwable> onError) {
        start(llmFlux, mysqlBuffer, flushPartial, onComplete, onError,
                new AtomicReference<>(ExecutionMode.REACT));
    }

    public void start(Flux<StreamToken> llmFlux, StringBuilder mysqlBuffer,
            Consumer<String> flushPartial, Runnable onComplete, Consumer<Throwable> onError,
            AtomicReference<ExecutionMode> executionMode) {
        this.mysqlBufferRef = mysqlBuffer;
        this.reasoningBufferRef = new StringBuilder();
        this.thinkMapper = new ThinkStepMapper(stepsBuffer, userQuery, executionMode);
        streamService.updateStatus(generationId, GenerationStatus.RUNNING);

        AtomicLong lastFlush = new AtomicLong(0);

        llmSubscription = llmFlux
                .subscribe(
                        chunk -> onChunk(chunk, mysqlBuffer, flushPartial, lastFlush),
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
        finishOnce(() -> {
            cancelOrphanTimer();
            disposeLlmSubscription();
            emitFinishSteps(true);
            streamService.updateStatus(generationId, GenerationStatus.INTERRUPTED);
            persistFinal(MessageStatus.INTERRUPTED, () -> { });
        });
    }

    private void onChunk(StreamToken token, StringBuilder mysqlBuffer,
            Consumer<String> flushPartial, AtomicLong lastFlush) {
        if (token.isStep() || token.isStepDelta()) {
            if (token.isStep()) {
                thinkMapper.syncExternalStep(token.step());
            }
            emitMappedChunk(token, mysqlBuffer, flushPartial, lastFlush);
            return;
        }
        for (StreamToken mapped : thinkMapper.map(token)) {
            emitMappedChunk(mapped, mysqlBuffer, flushPartial, lastFlush);
        }
    }

    private void emitMappedChunk(StreamToken token, StringBuilder mysqlBuffer,
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
            if ("reasoning".equals(token.channel()) && reasoningBufferRef != null) {
                reasoningBufferRef.append(token.text());
            }
            return;
        }
        String wire = token.isContent()
                ? flushScheduler.metaContent(token.text())
                : flushScheduler.metaReasoning(token.text());
        streamService.appendChunk(generationId, nextSeq, wire);
        if (token.isReasoning()) {
            if (reasoningBufferRef != null) {
                reasoningBufferRef.append(token.text());
            }
            return;
        }
        mysqlBuffer.append(token.text());

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
        streamService.updateStatus(generationId, GenerationStatus.FAILED);
        persistFinal(MessageStatus.FAILED, () -> onError.accept(error));
    }

    /** commitFinal 含脱敏 block 调用，须在 boundedElastic 执行，避免 reactor 线程 IllegalStateException */
    private void persistFinal(String status, Runnable afterPersist) {
        String content = bufferContent();
        String reasoning = bufferReasoning();
        String steps = stepsJson();
        Mono.fromRunnable(() -> {
                    flushScheduler.commitFinal(messageId, content, reasoning, status, steps);
                    afterPersist.run();
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
                    content -> flushScheduler.flushPartial(messageId, content), lastFlush);
        }
    }
}
