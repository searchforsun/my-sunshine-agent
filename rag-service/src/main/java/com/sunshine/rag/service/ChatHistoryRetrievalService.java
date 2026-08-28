package com.sunshine.rag.service;

import com.sunshine.rag.chunker.ChunkDraft;
import com.sunshine.rag.chunker.ChunkParams;
import com.sunshine.rag.chunker.ChunkStrategy;
import com.sunshine.rag.chunker.FixedLengthChunker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 对话历史 chunk：FIXED 分块 → embedding → Milvus upsert/search/delete。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatHistoryRetrievalService {

    private final EmbeddingService embeddingService;
    private final ChatHistoryMilvusService milvusService;
    private final FixedLengthChunker fixedLengthChunker;

    public Mono<List<ChatHistoryMilvusService.ChatHistoryHit>> search(
            String userId, String tenantId, String query, int topK) {
        return search(userId, tenantId, null, query, topK);
    }

    /** convId 非空 → 会话级过滤（session_search scope=session） */
    public Mono<List<ChatHistoryMilvusService.ChatHistoryHit>> search(
            String userId, String tenantId, String convId, String query, int topK) {
        return search(userId, tenantId, convId, null, null, query, topK);
    }

    /** scene/layers 非空时按 scene + layer IN 过滤（scene=chat|task；layers 如 body/semantic/process）。 */
    public Mono<List<ChatHistoryMilvusService.ChatHistoryHit>> search(
            String userId,
            String tenantId,
            String convId,
            String scene,
            List<String> layers,
            String query,
            int topK) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(query)) {
            return Mono.just(List.of());
        }
        String tid = tenantId != null ? tenantId : "default";
        int k = Math.max(1, topK);
        return embeddingService.embed(query)
                .map(vector -> milvusService.search(userId, tid, convId, scene, layers, vector, k))
                .doOnSuccess(h -> log.info("[ChatHistory] 召回 user={} conv={} scene={} layers={} hits={}",
                        userId, convId != null ? convId : "-", scene != null ? scene : "-",
                        layers != null ? layers : "-", h.size()))
                .onErrorResume(e -> {
                    log.warn("[ChatHistory] search 失败: {}", e.getMessage());
                    return Mono.just(List.of());
                });
    }

    /** convIds 非空时按会话列表过滤（session_search scope=workspace 跨会话正文）。 */
    public Mono<List<ChatHistoryMilvusService.ChatHistoryHit>> search(
            String userId,
            String tenantId,
            List<String> convIds,
            String scene,
            List<String> layers,
            String query,
            int topK) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(query)
                || convIds == null || convIds.isEmpty()) {
            return Mono.just(List.of());
        }
        String tid = tenantId != null ? tenantId : "default";
        int k = Math.max(1, topK);
        return embeddingService.embed(query)
                .map(vector -> milvusService.search(userId, tid, convIds, scene, layers, vector, k))
                .doOnSuccess(h -> log.info("[ChatHistory] 召回 user={} convs={} scene={} layers={} hits={}",
                        userId, convIds.size(), scene != null ? scene : "-",
                        layers != null ? layers : "-", h.size()))
                .onErrorResume(e -> {
                    log.warn("[ChatHistory] search(convs) 失败: {}", e.getMessage());
                    return Mono.just(List.of());
                });
    }

    public Mono<Void> upsert(
            String userId,
            String tenantId,
            String convId,
            String msgId,
            String content,
            long createdAtMs) {
        return upsert(userId, tenantId, convId, msgId, content, createdAtMs, "chat", "body", false);
    }

    /** scene=chat|task；layer=body|semantic|process；dedupe=true 时入库前按最近 24h 向量 cosine 去重。 */
    public Mono<Void> upsert(
            String userId,
            String tenantId,
            String convId,
            String msgId,
            String content,
            long createdAtMs,
            String scene,
            String layer,
            boolean dedupe) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(msgId) || !StringUtils.hasText(content)) {
            return Mono.empty();
        }
        String tid = tenantId != null ? tenantId : "default";
        String cid = convId != null ? convId : "";
        String sc = scene != null ? scene : "chat";
        String ly = layer != null ? layer : "body";
        ChunkParams params = ChunkParams.forStrategy(ChunkStrategy.FIXED, Map.of());
        List<ChunkDraft> drafts = fixedLengthChunker.chunk(content.strip(), params);
        if (drafts.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(drafts)
                .concatMap(draft -> embeddingService.embed(draft.text())
                        .map(vec -> new ChatHistoryMilvusService.ChunkRow(
                                draft.index(), draft.text(), vec)))
                .collectList()
                .flatMap(rows -> {
                    // 先清本 msgId 旧向量（重写不残留），再对库中其他向量去重，避免同 msgId 自碰撞
                    milvusService.deleteByMsgId(userId, tid, msgId);
                    if (!dedupe) {
                        return Mono.just(rows);
                    }
                    return dedupeRows(userId, tid, sc, ly, rows);
                })
                .doOnNext(rows -> {
                    if (rows.isEmpty()) {
                        log.debug("[ChatHistory] upsert dedupe 后全部跳过 msg={}", msgId);
                        return;
                    }
                    milvusService.insertChunks(userId, tid, cid, msgId, createdAtMs, rows, sc, ly);
                    log.info("[ChatHistory] upsert user={} msg={} scene={} layer={} chunks={}",
                            userId, msgId, sc, ly, rows.size());
                })
                .then()
                .onErrorResume(e -> {
                    log.warn("[ChatHistory] upsert 失败 msg={}: {}", msgId, e.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * 相似度去重：与最近 24h 同 scene 同 layer 已有向量 cosine 比对（layer 隔离——semantic/process 精炼段
     * 与 body 原文天然高相似，跨层比对会把提炼结果误判为完全重复而丢弃）；
     * >0.95 完全重复跳过 / 0.85~0.95 合并跳过（保留旧条）/ ≤0.85 正常写入。
     */
    private Mono<List<ChatHistoryMilvusService.ChunkRow>> dedupeRows(
            String userId, String tenantId, String scene, String layer,
            List<ChatHistoryMilvusService.ChunkRow> rows) {
        long since = System.currentTimeMillis() - 24L * 3600 * 1000;
        return Mono.fromCallable(() -> milvusService.queryRecentVectors(userId, tenantId, scene, layer, since, 50))
                .map(existing -> {
                    if (existing == null || existing.isEmpty()) {
                        return rows;
                    }
                    List<ChatHistoryMilvusService.ChunkRow> kept = new ArrayList<>();
                    int skipped = 0;
                    for (ChatHistoryMilvusService.ChunkRow row : rows) {
                        double best = 0.0;
                        for (ChatHistoryMilvusService.VectorRow old : existing) {
                            if (old.embedding() == null || old.embedding().isEmpty()) {
                                continue;
                            }
                            best = Math.max(best, cosine(row.embedding(), old.embedding()));
                        }
                        if (best > 0.95) {
                            skipped++;
                        } else if (best >= 0.85) {
                            // 合并：保留较早一条（旧条），当前条丢弃（§7.4.3）
                            skipped++;
                        } else {
                            kept.add(row);
                        }
                    }
                    if (skipped > 0) {
                        log.info("[ChatHistory] dedupe scene={} kept={} skipped={}",
                                scene, kept.size(), skipped);
                    }
                    return kept;
                })
                .onErrorResume(e -> {
                    log.warn("[ChatHistory] dedupe 查询失败，按全部写入处理: {}", e.getMessage());
                    return Mono.just(rows);
                });
    }

    private static double cosine(List<Float> a, List<Float> b) {
        int n = Math.min(a.size(), b.size());
        if (n == 0) {
            return 0.0;
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < n; i++) {
            double x = a.get(i);
            double y = b.get(i);
            dot += x * y;
            na += x * x;
            nb += y * y;
        }
        if (na == 0 || nb == 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    public Mono<Void> delete(String userId, String tenantId, String msgId) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(msgId)) {
            return Mono.empty();
        }
        String tid = tenantId != null ? tenantId : "default";
        return Mono.fromRunnable(() -> milvusService.deleteByMsgId(userId, tid, msgId))
                .then()
                .onErrorResume(e -> {
                    log.warn("[ChatHistory] delete 失败 msg={}: {}", msgId, e.getMessage());
                    return Mono.empty();
                });
    }

    /** 按 scene/layer/status 过滤删除（L3 定期维护：冲突向量清理 / 过期分层清理）。 */
    public Mono<Void> deleteByFilter(String userId, String tenantId, String scene, String layer, String status) {
        if (!StringUtils.hasText(userId)) {
            return Mono.empty();
        }
        String tid = tenantId != null ? tenantId : "default";
        return Mono.fromRunnable(() -> milvusService.deleteByFilter(userId, tid, scene, layer, status))
                .then()
                .onErrorResume(e -> {
                    log.warn("[ChatHistory] deleteByFilter 失败 user={} scene={} layer={}: {}",
                            userId, scene, layer, e.getMessage());
                    return Mono.empty();
                });
    }

    /** L3 定期维护：按 scene+layer 删除过期向量（分层 TTL，全局维度）。 */
    public Mono<Void> deleteExpired(String scene, String layer, long cutOffMs) {
        return Mono.fromRunnable(() -> milvusService.deleteExpired(scene, layer, cutOffMs))
                .then()
                .onErrorResume(e -> {
                    log.warn("[ChatHistory] deleteExpired 失败 scene={} layer={}: {}", scene, layer, e.getMessage());
                    return Mono.empty();
                });
    }

    public Mono<List<ChatHistoryMilvusService.ChatHistoryChunk>> listByConv(
            String userId, String tenantId, String convId, int limit) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(convId)) {
            return Mono.just(List.of());
        }
        String tid = tenantId != null ? tenantId : "default";
        return Mono.fromCallable(() -> milvusService.listByConv(userId, tid, convId, limit))
                .onErrorResume(e -> {
                    log.warn("[ChatHistory] listByConv 失败 conv={}: {}", convId, e.getMessage());
                    return Mono.just(List.of());
                });
    }
}
