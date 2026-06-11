package com.sunshine.orchestrator.generation;

import com.sunshine.orchestrator.config.ReactiveBlocking;
import com.sunshine.orchestrator.conversation.ConversationNotFoundException;
import com.sunshine.orchestrator.conversation.GenerationFlushScheduler;
import com.sunshine.orchestrator.conversation.MessageStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequiredArgsConstructor
@ConditionalOnBean(StringRedisTemplate.class)
public class GenerationController {

    private final GenerationStreamService streamService;
    private final GenerationRegistry registry;
    private final GenerationFlushScheduler flushScheduler;
    private final GenerationProperties generationProperties;

    @GetMapping("/generations/{id}")
    public Mono<GenerationStatusResponse> getStatus(
            @PathVariable("id") String id,
            @RequestHeader(value = "x-user-id", defaultValue = "anonymous") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return ReactiveBlocking.call(() -> {
            streamService.assertOwned(id, userId, tenantId);
            GenerationMeta meta = streamService.getMeta(id)
                    .orElseThrow(() -> new ConversationNotFoundException("generation不存在"));
            return GenerationStatusResponse.from(meta);
        });
    }

    @PostMapping("/generations/{id}/cancel")
    public Mono<Map<String, String>> cancel(
            @PathVariable("id") String id,
            @RequestHeader(value = "x-user-id", defaultValue = "anonymous") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return ReactiveBlocking.call(() -> {
            streamService.assertOwned(id, userId, tenantId);
            if (registry.get(id).isPresent()) {
                registry.cancel(id);
            } else if (streamService.getMeta(id).isPresent()) {
                streamService.updateStatus(id, GenerationStatus.INTERRUPTED);
            }
            return Map.of("status", GenerationStatus.INTERRUPTED.name());
        });
    }

    @GetMapping(value = "/chat/stream/{generationId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> reconnectStream(
            @PathVariable String generationId,
            @RequestParam(defaultValue = "0") long afterSeq,
            @RequestHeader(value = "x-user-id", defaultValue = "anonymous") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return ReactiveBlocking.call(() -> {
            streamService.assertOwned(generationId, userId, tenantId);
            GenerationMeta meta = streamService.getMeta(generationId)
                    .orElseThrow(() -> new ConversationNotFoundException("generation不存在"));
            if (meta.status() == GenerationStatus.INTERRUPTED || meta.status() == GenerationStatus.FAILED) {
                throw new GenerationGoneException("generation 已停止");
            }
            return meta;
        }).flatMapMany(meta -> buildReconnectFlux(meta, generationId, afterSeq));
    }

    private Flux<ServerSentEvent<String>> buildReconnectFlux(
            GenerationMeta meta, String generationId, long afterSeq) {

        List<StreamEvent> existing = streamService.readFrom(
                generationId, afterSeq, generationProperties.maxBufferChunks());
        AtomicLong lastEmittedSeq = new AtomicLong(
                existing.stream().mapToLong(StreamEvent::seq).max().orElse(afterSeq));

        Flux<ServerSentEvent<String>> historical = Flux.fromIterable(existing)
                .doOnNext(e -> lastEmittedSeq.updateAndGet(cur -> Math.max(cur, e.seq())))
                .map(e -> sseWithId(String.valueOf(e.seq()), e.text()));

        Flux<ServerSentEvent<String>> body;
        if (meta.status() == GenerationStatus.RUNNING || meta.status() == GenerationStatus.CREATED) {
            long subscribeAfter = lastEmittedSeq.get();
            Flux<ServerSentEvent<String>> live = streamService.subscribe(generationId, subscribeAfter)
                    .doOnNext(e -> lastEmittedSeq.set(e.seq()))
                    .takeUntilOther(
                            Flux.interval(Duration.ofMillis(50))
                                    .filter(t -> isCaughtUpAndTerminal(generationId, lastEmittedSeq.get()))
                                    .take(1))
                    .map(e -> sseWithId(String.valueOf(e.seq()), e.text()));
            body = Flux.concat(historical, live, doneMeta(meta.messageId(), generationId));
        } else {
            body = Flux.concat(historical, doneMeta(meta.messageId(), generationId));
        }

        return body
                .doOnSubscribe(s -> registry.get(generationId).ifPresent(GenerationJob::onSubscriberAttached))
                .doOnCancel(() -> registry.get(generationId).ifPresent(GenerationJob::onSubscriberGone));
    }

    private Flux<ServerSentEvent<String>> doneMeta(String messageId, String generationId) {
        return Flux.defer(() -> Flux.just(
                sse(flushScheduler.metaMessage(messageId, resolveFinalStatus(generationId), false))));
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

    private ServerSentEvent<String> sseWithId(String id, String data) {
        return ServerSentEvent.<String>builder()
                .id(id)
                .data(data)
                .build();
    }

    private ServerSentEvent<String> sse(String data) {
        return ServerSentEvent.<String>builder()
                .data(data)
                .build();
    }
}
