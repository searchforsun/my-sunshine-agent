package com.sunshine.rag.admin.catalog;

import com.sunshine.rag.admin.catalog.dto.ChunkPreviewDto;
import com.sunshine.rag.chunker.ChunkDraft;
import com.sunshine.rag.chunker.ChunkStrategy;
import com.sunshine.rag.entity.DocumentEntity;
import com.sunshine.rag.model.ChunkInsertRequest;
import com.sunshine.rag.service.ElasticsearchIndexService;
import com.sunshine.rag.service.EmbeddingService;
import com.sunshine.rag.service.MilvusService;
import com.sunshine.rag.util.ChunkIds;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 文档 chunk 向量写入与预览查询 */
@Service
@RequiredArgsConstructor
public class DocumentChunkIndexer {

    private final EmbeddingService embeddingService;
    private final MilvusService milvusService;
    private final ElasticsearchIndexService elasticsearchIndexService;

    public List<ChunkPreviewDto> listChunks(DocumentEntity doc, String version, String store) {
        if ("es".equalsIgnoreCase(store)) {
            return elasticsearchIndexService.queryChunksByDocVersion(
                            doc.getTenantId(), doc.getKbId(), doc.getDocId(), version).stream()
                    .map(row -> new ChunkPreviewDto(
                            row.get("chunk_index") instanceof Number n ? n.intValue() : 0,
                            row.get("doc_name") != null ? row.get("doc_name").toString() : doc.getDocId(),
                            row.get("content") != null ? row.get("content").toString() : ""))
                    .sorted(Comparator.comparingInt(ChunkPreviewDto::chunkIndex))
                    .toList();
        }
        return milvusService.queryChunks(doc.getTenantId(), doc.getKbId(), doc.getDocId(), version).stream()
                .map(c -> new ChunkPreviewDto(c.chunkIndex(), c.docName(), c.content()))
                .toList();
    }

    public Mono<Void> embedAndIndexDrafts(
            String tenantId,
            String kbId,
            String docId,
            String docName,
            String version,
            List<ChunkDraft> drafts,
            ChunkStrategy strategy) {
        if (drafts.isEmpty()) {
            return Mono.empty();
        }
        if (strategy == null) {
            throw new IllegalArgumentException("chunk strategy required");
        }
        String strategyWire = strategy.wire();
        return Flux.fromIterable(drafts)
                .flatMap(draft -> {
                    String chunkId = ChunkIds.chunkId(docId, version, draft.index());
                    String chunkLevel = resolveChunkLevel(strategy, draft.meta());
                    String parentChunkId = parentChunkIdFromMeta(docId, version, draft.meta());
                    return embeddingService.embed(draft.text())
                            .doOnNext(vector -> {
                                ChunkInsertRequest req = new ChunkInsertRequest(
                                        docName, draft.text(), vector, tenantId, kbId, docId,
                                        version, draft.index(), "active", strategyWire,
                                        strategyWire, chunkLevel, parentChunkId);
                                milvusService.insert(req);
                                elasticsearchIndexService.indexChunk(
                                        chunkId, docName, draft.text(), draft.index(), tenantId,
                                        kbId, docId, version, "active", strategyWire,
                                        strategyWire, chunkLevel, parentChunkId);
                            });
                })
                .then();
    }

    /** 非父子策略一律标 chunk；父子策略用 meta.level（parent/child） */
    static String resolveChunkLevel(ChunkStrategy strategy, Map<String, Object> meta) {
        if (strategy == ChunkStrategy.PARENT_CHILD) {
            if (meta == null) {
                throw new IllegalArgumentException("parent_child chunk meta.level required");
            }
            Object level = meta.get("level");
            if (level == null) {
                throw new IllegalArgumentException("parent_child chunk meta.level required");
            }
            String wire = level.toString();
            if (!"parent".equals(wire) && !"child".equals(wire)) {
                throw new IllegalArgumentException("parent_child chunk meta.level must be parent|child");
            }
            return wire;
        }
        return "chunk";
    }

    static String parentChunkIdFromMeta(String docId, String version, Map<String, Object> meta) {
        if (meta == null || !"child".equals(meta.get("level"))) {
            return "";
        }
        Object parentIndex = meta.get("parentIndex");
        if (!(parentIndex instanceof Number n)) {
            return "";
        }
        return ChunkIds.parentChunkId(docId, version, n.intValue());
    }

    public void purgeIndexedVersion(String tenantId, String kbId, String docId, String version) {
        milvusService.deleteByDocVersion(tenantId, kbId, docId, version);
        elasticsearchIndexService.deleteByDocVersion(tenantId, kbId, docId, version);
    }
}
