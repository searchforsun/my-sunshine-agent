package com.sunshine.orchestrator.generation;

import com.sunshine.orchestrator.conversation.GenerationFlushScheduler;
import com.sunshine.orchestrator.conversation.MessageStatus;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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
    private final GenerationStreamService streamService;
    private final GenerationProperties properties;
    private final GenerationFlushScheduler flushScheduler;

    private final AtomicLong seq = new AtomicLong(0);
    private final AtomicBoolean finished = new AtomicBoolean(false);

    private volatile Disposable llmSubscription;
    private volatile Disposable orphanTimer;
    private volatile StringBuilder mysqlBufferRef;

    GenerationJob(String generationId, String messageId, String conversationId,
            String userId, String tenantId, String intent,
            GenerationStreamService streamService,
            GenerationProperties properties,
            GenerationFlushScheduler flushScheduler) {
        this.generationId = generationId;
        this.messageId = messageId;
        this.conversationId = conversationId;
        this.userId = userId;
        this.tenantId = tenantId;
        this.intent = intent;
        this.streamService = streamService;
        this.properties = properties;
        this.flushScheduler = flushScheduler;
    }

    public void start(Flux<String> llmFlux, StringBuilder mysqlBuffer,
            Consumer<String> flushPartial, Runnable onComplete, Consumer<Throwable> onError) {
        this.mysqlBufferRef = mysqlBuffer;
        streamService.updateStatus(generationId, GenerationStatus.RUNNING);

        AtomicLong lastFlush = new AtomicLong(0);

        llmSubscription = llmFlux
                .publishOn(Schedulers.boundedElastic())
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
            streamService.updateStatus(generationId, GenerationStatus.INTERRUPTED);
            flushScheduler.commitFinal(messageId, bufferContent(), MessageStatus.INTERRUPTED);
        });
    }

    private void onChunk(String chunk, StringBuilder mysqlBuffer,
            Consumer<String> flushPartial, AtomicLong lastFlush) {
        long nextSeq = seq.incrementAndGet();
        streamService.appendChunk(generationId, nextSeq, chunk);
        mysqlBuffer.append(chunk);

        long now = System.currentTimeMillis();
        if (now - lastFlush.get() >= properties.flushIntervalMs()) {
            lastFlush.set(now);
            flushPartial.accept(mysqlBuffer.toString());
        }
    }

    private void handleComplete(Runnable onComplete) {
        cancelOrphanTimer();
        disposeLlmSubscription();
        streamService.updateStatus(generationId, GenerationStatus.COMPLETED);
        flushScheduler.commitFinal(messageId, bufferContent(), MessageStatus.COMPLETED);
        onComplete.run();
    }

    private void handleError(Throwable error, Consumer<Throwable> onError) {
        cancelOrphanTimer();
        disposeLlmSubscription();
        streamService.updateStatus(generationId, GenerationStatus.FAILED);
        flushScheduler.commitFinal(messageId, bufferContent(), MessageStatus.FAILED);
        onError.accept(error);
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
}
