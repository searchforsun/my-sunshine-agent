package com.sunshine.orchestrator.generation;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.orchestrator.exception.OrchestratorErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@RequiredArgsConstructor
public class GenerationStreamService {

    private static final String META_KEY_PREFIX = "sunshine:gen:";
    private static final String EVENTS_KEY_SUFFIX = ":events";

    private final StringRedisTemplate redis;
    private final GenerationProperties properties;

    public String createGeneration(String conversationId, String messageId,
            String userId, String tenantId, String intent) {
        String generationId = UUID.randomUUID().toString();
        String metaKey = metaKey(generationId);
        Map<String, String> fields = Map.of(
                "conversationId", conversationId,
                "messageId", messageId,
                "userId", userId,
                "tenantId", normalizeTenant(tenantId),
                "status", GenerationStatus.CREATED.name(),
                "lastSeq", "0",
                "intent", intent != null ? intent : ""
        );
        redis.opsForHash().putAll(metaKey, fields);
        refreshTtl(generationId);
        return generationId;
    }

    public void appendChunk(String generationId, long seq, String text) {
        String eventsKey = eventsKey(generationId);
        MapRecord<String, String, String> record = StreamRecords.string(
                Map.of("seq", String.valueOf(seq), "text", text))
                .withStreamKey(eventsKey)
                .withId(RecordId.of(streamId(seq)));

        redis.opsForStream().add(record);
        redis.opsForHash().put(metaKey(generationId), "lastSeq", String.valueOf(seq));
        refreshTtl(generationId);
    }

    public void updateStatus(String generationId, GenerationStatus status) {
        redis.opsForHash().put(metaKey(generationId), "status", status.name());
        refreshTtl(generationId);
    }

    public Optional<GenerationMeta> getMeta(String generationId) {
        Map<Object, Object> raw = redis.opsForHash().entries(metaKey(generationId));
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toMeta(generationId, raw));
    }

    public List<StreamEvent> readFrom(String generationId, long afterSeq, int count) {
        String eventsKey = eventsKey(generationId);
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().range(
                eventsKey,
                rangeAfter(afterSeq),
                org.springframework.data.redis.connection.Limit.limit().count(count));

        List<StreamEvent> events = new ArrayList<>(records.size());
        for (MapRecord<String, Object, Object> record : records) {
            events.add(toStreamEvent(record));
        }
        return events;
    }

    public Flux<StreamEvent> subscribe(String generationId, long afterSeq) {
        AtomicLong cursor = new AtomicLong(afterSeq);
        Duration pollInterval = Duration.ofMillis(
                Math.min(properties.reconnectBlockMs(), 100));

        return Flux.interval(Duration.ZERO, pollInterval)
                .concatMap(tick -> Flux.defer(() -> {
                    long after = cursor.get();
                    List<StreamEvent> batch = readFrom(
                            generationId, after, properties.maxBufferChunks());
                    if (batch.isEmpty()) {
                        return Flux.empty();
                    }
                    return Flux.fromIterable(batch)
                            .doOnNext(e -> cursor.updateAndGet(cur -> Math.max(cur, e.seq())));
                }));
    }

    public void assertOwned(String generationId, String userId, String tenantId) {
        GenerationMeta meta = getMeta(generationId)
                .orElseThrow(() -> new BizException(OrchestratorErrorCode.GENERATION_NOT_FOUND));
        if (!meta.userId().equals(userId) || !meta.tenantId().equals(normalizeTenant(tenantId))) {
            throw new BizException(OrchestratorErrorCode.GENERATION_NOT_FOUND);
        }
    }

    private void refreshTtl(String generationId) {
        Duration ttl = Duration.ofSeconds(properties.ttlSec());
        redis.expire(metaKey(generationId), ttl);
        redis.expire(eventsKey(generationId), ttl);
    }

    private static String metaKey(String generationId) {
        return META_KEY_PREFIX + generationId;
    }

    private static String eventsKey(String generationId) {
        return META_KEY_PREFIX + generationId + EVENTS_KEY_SUFFIX;
    }

    private static String streamId(long seq) {
        return seq + "-0";
    }

    private static Range<String> rangeAfter(long afterSeq) {
        return Range.rightOpen("(" + streamId(afterSeq), "+");
    }

    private static String normalizeTenant(String tenantId) {
        return tenantId != null ? tenantId : "default";
    }

    private GenerationMeta toMeta(String generationId, Map<Object, Object> raw) {
        return new GenerationMeta(
                generationId,
                stringField(raw, "conversationId"),
                stringField(raw, "messageId"),
                stringField(raw, "userId"),
                stringField(raw, "tenantId"),
                GenerationStatus.valueOf(stringField(raw, "status")),
                longField(raw, "lastSeq"),
                stringField(raw, "intent")
        );
    }

    private static StreamEvent toStreamEvent(MapRecord<String, Object, Object> record) {
        Map<Object, Object> value = record.getValue();
        return new StreamEvent(longField(value, "seq"), stringField(value, "text"));
    }

    private static String stringField(Map<Object, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : "";
    }

    private static long longField(Map<Object, Object> map, String key) {
        String value = stringField(map, key);
        return value.isEmpty() ? 0L : Long.parseLong(value);
    }
}
