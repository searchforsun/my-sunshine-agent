package com.sunshine.orchestrator.generation;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.orchestrator.config.VirtualThreadExecutors;
import com.sunshine.orchestrator.exception.OrchestratorErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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

    /**
     * 写入事件流。RecordId 为显式 {@code {seq}-0}，调用方须保证同一 generationId 上
     * 「分配 seq → 本方法」严格按 seq 升序串行（见 {@code GenerationJob#streamAppendLock}）；
     * 工具并行不得改为关闭，只串行化写流。
     */
    public void appendChunk(String generationId, long seq, String text) {
        String eventsKey = eventsKey(generationId);
        MapRecord<String, String, String> record = StreamRecords.string(
                Map.of("seq", String.valueOf(seq), "text", text))
                .withStreamKey(eventsKey)
                .withId(RecordId.of(streamId(seq)));

        redis.opsForStream().add(record);
        redis.opsForHash().put(metaKey(generationId), "lastSeq", String.valueOf(seq));
        trimStreamIfNeeded(eventsKey, seq);
        refreshTtl(generationId);
    }

    /**
     * 近似裁剪事件流：长任务（pro/Planner-Executor）单 generation 可达数万条大 chunk
     * （spawnPrompt/content 每条约 KB），无界增长会撑爆 Redis maxmemory 触发 LRU 逐出
     * （曾逐出 meta hash 导致续连 SSE 500）。订阅方始终从 lastSeq 之后增量读、DB 有
     * steps 兜底，裁剪旧条目不影响续连。仅周期执行降低写放大。
     */
    private void trimStreamIfNeeded(String eventsKey, long seq) {
        long maxStreamLen = properties.maxStreamLen();
        if (maxStreamLen <= 0 || seq % 128 != 0) {
            return;
        }
        redis.opsForStream().trim(eventsKey, maxStreamLen, true);
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
        GenerationMeta meta = toMeta(generationId, raw);
        // status 解析失败（meta 被逐出/残缺）视为不存在：续连 SSE 走 empty 分支正常收尾
        if (meta.status() == null) {
            return Optional.empty();
        }
        return Optional.of(meta);
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

    /**
     * 订阅 generation 事件流直到终态。
     * 事件驱动轮询：每轮用 {@link Mono#fromCallable} 做一次阻塞读（虚拟线程），
     * 发出事件后经 repeatWhen 延迟 pollInterval 再读下一轮；终态经 Sinks 哨兵终止。
     * 切忌退回 `Flux.interval` + 同步阻塞轮询——固定节拍不等下游消费，
     * 慢消费者上会抛 `Could not emit tick due to lack of requests` 背压异常，
     * 导致续连 SSE 连接被强制关闭（「多刷新两次断流」根因）。
     * 也勿用 {@link Flux#generate} 做「空批次等待」：generator 每次调用必须调用 sink 方法。
     * meta 已删除（TTL/清理）同样视为终态，保证续连流能正常收尾。
     */
    public Flux<StreamEvent> subscribeToEnd(String generationId, long afterSeq) {
        Duration pollInterval = Duration.ofMillis(
                Math.min(properties.reconnectBlockMs(), 100));
        AtomicLong cursor = new AtomicLong(afterSeq);
        AtomicBoolean terminalSignalled = new AtomicBoolean(false);
        Sinks.Empty<Void> terminalSignal = Sinks.empty();

        Flux<StreamEvent> pollOnce = Mono.<List<StreamEvent>>fromCallable(() -> {
                    long after = cursor.get();
                    List<StreamEvent> batch = readFrom(
                            generationId, after, properties.maxBufferChunks());
                    if (!batch.isEmpty()) {
                        long maxSeq = batch.stream()
                                .mapToLong(StreamEvent::seq).max().orElse(after);
                        cursor.set(maxSeq);
                        return batch;
                    }
                    Optional<GenerationMeta> meta = getMeta(generationId);
                    boolean caughtUpTerminal = meta.isEmpty()
                            || (isTerminal(meta.get().status())
                                    && cursor.get() >= meta.get().lastSeq());
                    if (caughtUpTerminal && terminalSignalled.compareAndSet(false, true)) {
                        terminalSignal.tryEmitEmpty();
                    }
                    return List.<StreamEvent>of();
                })
                .subscribeOn(VirtualThreadExecutors.scheduler())
                .flatMapMany(Flux::fromIterable);

        return pollOnce
                .repeatWhen(completed -> completed.delayElements(pollInterval))
                .takeUntilOther(terminalSignal.asMono());
    }

    private static boolean isTerminal(GenerationStatus status) {
        return status == GenerationStatus.COMPLETED
                || status == GenerationStatus.FAILED
                || status == GenerationStatus.INTERRUPTED;
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
                parseStatus(raw),
                longField(raw, "lastSeq"),
                stringField(raw, "intent")
        );
    }

    /**
     * Redis 内存逐出等异常下 meta hash 可能残缺（仅剩 lastSeq），status 缺失或非法时
     * 整体视为不存在——由 {@link #getMeta} 返回 empty，续连 SSE 正常收尾而非 500 崩溃。
     */
    private GenerationStatus parseStatus(Map<Object, Object> raw) {
        String status = stringField(raw, "status");
        try {
            return GenerationStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            return null;
        }
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
