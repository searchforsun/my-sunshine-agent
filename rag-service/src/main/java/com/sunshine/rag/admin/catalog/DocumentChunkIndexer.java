package com.sunshine.rag.admin.catalog;

import com.sunshine.rag.admin.catalog.dto.ChunkPreviewDto;
import com.sunshine.rag.entity.DocumentEntity;
import com.sunshine.rag.model.ChunkInsertRequest;
import com.sunshine.rag.service.ElasticsearchIndexService;
import com.sunshine.rag.service.EmbeddingService;
import com.sunshine.rag.service.MilvusService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;

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

    public Mono<Void> embedAndIndex(
            String tenantId, String kbId, String docId, String docName, String version, List<String> chunks) {
        if (chunks.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(chunks)
                .index()
                .flatMap(tuple -> {
                    long index = tuple.getT1();
                    String chunk = tuple.getT2();
                    String chunkId = docId + "#v" + version + "#" + index;
                    return embeddingService.embed(chunk)
                            .doOnNext(vector -> {
                                ChunkInsertRequest req = new ChunkInsertRequest(
                                        docName, chunk, vector, tenantId, kbId, docId,
                                        version, (int) index, "active", "markdown");
                                milvusService.insert(req);
                                elasticsearchIndexService.indexChunk(
                                        chunkId, docName, chunk, (int) index, tenantId,
                                        kbId, docId, version, "active", "markdown");
                            });
                })
                .then();
    }

    public void purgeIndexedVersion(String tenantId, String kbId, String docId, String version) {
        milvusService.deleteByDocVersion(tenantId, kbId, docId, version);
        elasticsearchIndexService.deleteByDocVersion(tenantId, kbId, docId, version);
    }
}
