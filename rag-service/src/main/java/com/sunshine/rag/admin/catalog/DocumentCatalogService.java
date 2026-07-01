package com.sunshine.rag.admin.catalog;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.rag.admin.catalog.dto.ChunkPreviewDto;
import com.sunshine.rag.admin.catalog.dto.DocumentDetail;
import com.sunshine.rag.admin.catalog.dto.DocumentSummary;
import com.sunshine.rag.admin.catalog.dto.DocumentVersionSummary;
import com.sunshine.rag.admin.catalog.dto.IngestResult;
import com.sunshine.rag.admin.catalog.dto.IngestTextRequest;
import com.sunshine.rag.entity.DocumentEntity;
import com.sunshine.rag.entity.DocumentVersionEntity;
import com.sunshine.rag.exception.RagErrorCode;
import com.sunshine.rag.model.ChunkInsertRequest;
import com.sunshine.rag.parser.MarkdownParser;
import com.sunshine.rag.repository.DocumentRepository;
import com.sunshine.rag.repository.DocumentVersionRepository;
import com.sunshine.rag.service.ElasticsearchIndexService;
import com.sunshine.rag.service.EmbeddingService;
import com.sunshine.rag.service.MilvusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentCatalogService {

    private static final String DEFAULT_DOC_NAME = "未命名文档";

    private final KnowledgeBaseService knowledgeBaseService;
    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final MarkdownParser markdownParser;
    private final EmbeddingService embeddingService;
    private final MilvusService milvusService;
    private final ElasticsearchIndexService elasticsearchIndexService;

    public List<DocumentSummary> listDocuments(String tenantId, String kbId) {
        knowledgeBaseService.requireKb(tenantId, kbId);
        String tid = KnowledgeBaseService.normalizeTenant(tenantId);
        String kid = KnowledgeBaseService.requireKbId(kbId);
        return documentRepository.findByTenantIdAndKbIdOrderByDocIdAsc(tid, kid).stream()
                .map(doc -> {
                    int activeVersion = 0;
                    int chunkCount = 0;
                    Optional<DocumentVersionEntity> active = documentVersionRepository
                            .findFirstByTenantIdAndKbIdAndDocIdAndStatusOrderByVersionDesc(
                                    tid, kid, doc.getDocId(), "active");
                    if (active.isPresent()) {
                        activeVersion = active.get().getVersion();
                        chunkCount = active.get().getChunkCount();
                    }
                    return new DocumentSummary(
                            doc.getDocId(), doc.getDisplayName(), doc.getSourceType(), activeVersion, chunkCount);
                })
                .toList();
    }

    public DocumentDetail getDocument(String tenantId, String kbId, String docId) {
        DocumentEntity doc = requireDocument(tenantId, kbId, docId);
        String tid = doc.getTenantId();
        String kid = doc.getKbId();
        List<DocumentVersionSummary> versions = documentVersionRepository
                .findByTenantIdAndKbIdAndDocIdOrderByVersionDesc(tid, kid, doc.getDocId()).stream()
                .map(v -> new DocumentVersionSummary(
                        v.getVersion(),
                        v.getStatus(),
                        v.getChunkCount(),
                        v.getPublishedAt() != null ? v.getPublishedAt().toString() : null))
                .toList();
        return new DocumentDetail(doc.getDocId(), doc.getDisplayName(), doc.getSourceType(), versions);
    }

    public List<ChunkPreviewDto> listChunks(String tenantId, String kbId, String docId, Integer version) {
        DocumentEntity doc = requireDocument(tenantId, kbId, docId);
        int ver = resolveVersion(doc, version);
        return milvusService.queryChunks(doc.getTenantId(), doc.getKbId(), doc.getDocId(), ver).stream()
                .map(c -> new ChunkPreviewDto(c.chunkIndex(), c.docName(), c.content()))
                .toList();
    }

    @Transactional
    public Mono<IngestResult> ingestText(String tenantId, String kbId, IngestTextRequest request) {
        if (request == null || !StringUtils.hasText(request.content())) {
            throw new BizException(RagErrorCode.CONTENT_EMPTY);
        }
        knowledgeBaseService.requireKb(tenantId, kbId);
        String tid = KnowledgeBaseService.normalizeTenant(tenantId);
        String kid = KnowledgeBaseService.requireKbId(kbId);
        String content = request.content();
        String docName = resolveDocName(request);
        String docId = resolveDocId(request, docName);
        String displayName = StringUtils.hasText(request.displayName()) ? request.displayName().strip() : docName;
        DocumentEntity document = documentRepository.findByTenantIdAndKbIdAndDocId(tid, kid, docId)
                .orElseGet(() -> createDocument(tid, kid, docId, displayName));
        if (StringUtils.hasText(displayName) && !displayName.equals(document.getDisplayName())) {
            document.setDisplayName(displayName);
            document.setUpdatedAt(Instant.now());
            documentRepository.save(document);
        }
        supersedeActiveVersions(tid, kid, docId);
        int newVersion = nextVersion(tid, kid, docId);
        List<String> chunks = markdownParser.parse(content);
        DocumentVersionEntity versionEntity = new DocumentVersionEntity();
        versionEntity.setTenantId(tid);
        versionEntity.setKbId(kid);
        versionEntity.setDocId(docId);
        versionEntity.setVersion(newVersion);
        versionEntity.setStatus("active");
        versionEntity.setParsedMarkdown(content);
        versionEntity.setChunkCount(chunks.size());
        versionEntity.setPublishedAt(Instant.now());
        documentVersionRepository.save(versionEntity);
        log.info("[RAG] catalog ingest: tenant={}, kb={}, doc={}, v={}, chunks={}",
                tid, kid, docId, newVersion, chunks.size());
        return embedAndIndex(tid, kid, docId, docName, newVersion, chunks)
                .thenReturn(new IngestResult(docId, docName, newVersion, chunks.size()));
    }

    /** 兼容 POST /api/rag/documents */
    public Mono<Map<String, Object>> ingestLegacy(String tenantId, Map<String, String> body) {
        IngestTextRequest request = new IngestTextRequest(
                body.get("content"),
                null,
                body.get("docName") != null ? body.get("docName") : body.get("title"),
                body.get("docName") != null ? body.get("docName") : body.get("title"));
        return ingestText(tenantId, "default", request)
                .map(r -> Map.<String, Object>of("docName", r.docName(), "chunks", r.chunks()));
    }

    @Transactional
    public void supersedeVersion(String tenantId, String kbId, String docId, int version) {
        requireDocument(tenantId, kbId, docId);
        String tid = KnowledgeBaseService.normalizeTenant(tenantId);
        String kid = KnowledgeBaseService.requireKbId(kbId);
        DocumentVersionEntity entity = documentVersionRepository
                .findByTenantIdAndKbIdAndDocIdAndVersion(tid, kid, docId, version)
                .orElseThrow(() -> new BizException(RagErrorCode.VERSION_NOT_FOUND));
        markSuperseded(entity);
    }

    private Mono<Void> embedAndIndex(
            String tenantId, String kbId, String docId, String docName, int version, List<String> chunks) {
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

    private void supersedeActiveVersions(String tenantId, String kbId, String docId) {
        List<DocumentVersionEntity> active = documentVersionRepository
                .findByTenantIdAndKbIdAndDocIdAndStatus(tenantId, kbId, docId, "active");
        for (DocumentVersionEntity v : active) {
            markSuperseded(v);
        }
    }

    private void markSuperseded(DocumentVersionEntity entity) {
        if ("superseded".equals(entity.getStatus())) {
            return;
        }
        milvusService.deleteByDocVersion(
                entity.getTenantId(), entity.getKbId(), entity.getDocId(), entity.getVersion());
        elasticsearchIndexService.deleteByDocVersion(
                entity.getTenantId(), entity.getKbId(), entity.getDocId(), entity.getVersion());
        entity.setStatus("superseded");
        documentVersionRepository.save(entity);
    }

    private DocumentEntity createDocument(String tenantId, String kbId, String docId, String displayName) {
        DocumentEntity entity = new DocumentEntity();
        entity.setTenantId(tenantId);
        entity.setKbId(kbId);
        entity.setDocId(docId);
        entity.setDisplayName(displayName);
        entity.setSourceType("markdown");
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        return documentRepository.save(entity);
    }

    private DocumentEntity requireDocument(String tenantId, String kbId, String docId) {
        knowledgeBaseService.requireKb(tenantId, kbId);
        String tid = KnowledgeBaseService.normalizeTenant(tenantId);
        String kid = KnowledgeBaseService.requireKbId(kbId);
        if (!StringUtils.hasText(docId)) {
            throw new BizException(RagErrorCode.DOC_NOT_FOUND);
        }
        return documentRepository.findByTenantIdAndKbIdAndDocId(tid, kid, docId.strip())
                .orElseThrow(() -> new BizException(RagErrorCode.DOC_NOT_FOUND));
    }

    private int nextVersion(String tenantId, String kbId, String docId) {
        return documentVersionRepository.findByTenantIdAndKbIdAndDocIdOrderByVersionDesc(tenantId, kbId, docId).stream()
                .mapToInt(DocumentVersionEntity::getVersion)
                .max()
                .orElse(0) + 1;
    }

    private int resolveVersion(DocumentEntity doc, Integer version) {
        if (version != null && version > 0) {
            return version;
        }
        return documentVersionRepository
                .findFirstByTenantIdAndKbIdAndDocIdAndStatusOrderByVersionDesc(
                        doc.getTenantId(), doc.getKbId(), doc.getDocId(), "active")
                .map(DocumentVersionEntity::getVersion)
                .orElseThrow(() -> new BizException(RagErrorCode.VERSION_NOT_FOUND));
    }

    private static String resolveDocName(IngestTextRequest request) {
        if (StringUtils.hasText(request.docName())) {
            return request.docName().strip();
        }
        if (StringUtils.hasText(request.displayName())) {
            return request.displayName().strip();
        }
        String content = request.content();
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ")) {
                return trimmed.substring(2).trim();
            }
        }
        return DEFAULT_DOC_NAME;
    }

    private static String resolveDocId(IngestTextRequest request, String docName) {
        if (StringUtils.hasText(request.docId())) {
            return request.docId().strip();
        }
        return docName;
    }
}
