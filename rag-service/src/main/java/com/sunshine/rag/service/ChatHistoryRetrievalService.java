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
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(query)) {
            return Mono.just(List.of());
        }
        String tid = tenantId != null ? tenantId : "default";
        int k = Math.max(1, topK);
        return embeddingService.embed(query)
                .map(vector -> milvusService.search(userId, tid, vector, k))
                .doOnSuccess(h -> log.info("[ChatHistory] 召回 user={} hits={}", userId, h.size()))
                .onErrorResume(e -> {
                    log.warn("[ChatHistory] search 失败: {}", e.getMessage());
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
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(msgId) || !StringUtils.hasText(content)) {
            return Mono.empty();
        }
        String tid = tenantId != null ? tenantId : "default";
        String cid = convId != null ? convId : "";
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
                .doOnNext(rows -> {
                    milvusService.deleteByMsgId(userId, tid, msgId);
                    milvusService.insertChunks(userId, tid, cid, msgId, createdAtMs, rows);
                    log.info("[ChatHistory] upsert user={} msg={} chunks={}", userId, msgId, rows.size());
                })
                .then()
                .onErrorResume(e -> {
                    log.warn("[ChatHistory] upsert 失败 msg={}: {}", msgId, e.getMessage());
                    return Mono.empty();
                });
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
