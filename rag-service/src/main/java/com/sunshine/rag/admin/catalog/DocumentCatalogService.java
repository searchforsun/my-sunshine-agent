package com.sunshine.rag.admin.catalog;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.rag.admin.catalog.dto.ChunkPreviewDto;
import com.sunshine.rag.admin.catalog.dto.CreateDocumentRequest;
import com.sunshine.rag.admin.catalog.dto.DocumentContentView;
import com.sunshine.rag.admin.catalog.dto.DocumentDetail;
import com.sunshine.rag.admin.catalog.dto.DocumentSummary;
import com.sunshine.rag.admin.catalog.dto.DocumentVersionSummary;
import com.sunshine.rag.admin.catalog.dto.IngestResult;
import com.sunshine.rag.admin.catalog.dto.IngestTextRequest;
import com.sunshine.rag.admin.catalog.dto.SaveDocumentContentRequest;
import com.sunshine.rag.admin.catalog.dto.UpdateDocumentRequest;
import com.sunshine.rag.admin.config.EffectiveConfigResolver;
import com.sunshine.rag.entity.DocumentEntity;
import com.sunshine.rag.entity.DocumentVersionEntity;
import com.sunshine.rag.exception.RagErrorCode;
import com.sunshine.rag.model.ChunkInsertRequest;
import com.sunshine.rag.client.DesensitizeClient;
import com.sunshine.rag.admin.catalog.parser.DocumentFileParser;
import com.sunshine.rag.parser.MarkdownParser;
import com.sunshine.rag.repository.DocumentRepository;
import com.sunshine.rag.repository.DocumentVersionRepository;
import com.sunshine.rag.repository.IngestJobRepository;
import com.sunshine.rag.service.ElasticsearchIndexService;
import com.sunshine.rag.service.EmbeddingService;
import com.sunshine.rag.service.MilvusService;
import com.sunshine.rag.storage.RagStorageFacade;
import com.sunshine.rag.util.DocumentVersionTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentCatalogService {

    private static final String DEFAULT_DOC_NAME = "未命名文档";

    private final KnowledgeBaseService knowledgeBaseService;
    private final EffectiveConfigResolver effectiveConfigResolver;
    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final IngestJobRepository ingestJobRepository;
    private final MarkdownParser markdownParser;
    private final DocumentFileParser documentFileParser;
    private final EmbeddingService embeddingService;
    private final MilvusService milvusService;
    private final ElasticsearchIndexService elasticsearchIndexService;
    private final RagStorageFacade ragStorageFacade;
    private final DesensitizeClient desensitizeClient;

    @Lazy
    @Autowired
    DocumentCatalogService self;

    public List<DocumentSummary> listDocuments(String tenantId, String kbId) {
        knowledgeBaseService.requireKb(tenantId, kbId);
        String tid = KnowledgeBaseService.normalizeTenant(tenantId);
        String kid = KnowledgeBaseService.requireKbId(kbId);
        return documentRepository.findByTenantIdAndKbIdOrderByDocIdAsc(tid, kid).stream()
                .map(doc -> {
                    String activeVersion = doc.getActiveVersion();
                    int chunkCount = 0;
                    if (StringUtils.hasText(activeVersion)) {
                        chunkCount = documentVersionRepository
                                .findByTenantIdAndKbIdAndDocIdAndVersion(tid, kid, doc.getDocId(), activeVersion)
                                .map(DocumentVersionEntity::getChunkCount)
                                .orElse(0);
                    } else {
                        Optional<DocumentVersionEntity> active = documentVersionRepository
                                .findFirstByTenantIdAndKbIdAndDocIdAndStatusOrderByVersionDesc(
                                        tid, kid, doc.getDocId(), "active");
                        if (active.isPresent()) {
                            activeVersion = active.get().getVersion();
                            chunkCount = active.get().getChunkCount();
                        }
                    }
                    return new DocumentSummary(
                            doc.getDocId(), doc.getDisplayName(), doc.getSourceType(), activeVersion, chunkCount);
                })
                .toList();
    }

    @Transactional
    public DocumentDetail createDocument(String tenantId, String kbId, CreateDocumentRequest request) {
        if (request == null || !StringUtils.hasText(request.docId()) || !StringUtils.hasText(request.displayName())) {
            throw new BizException(RagErrorCode.DOC_ID_DISPLAY_NAME_REQUIRED);
        }
        knowledgeBaseService.requireKb(tenantId, kbId);
        String tid = KnowledgeBaseService.normalizeTenant(tenantId);
        String kid = KnowledgeBaseService.requireKbId(kbId);
        String docId = request.docId().strip();
        if (documentRepository.findByTenantIdAndKbIdAndDocId(tid, kid, docId).isPresent()) {
            throw new BizException(RagErrorCode.DOC_ALREADY_EXISTS);
        }
        DocumentSourceType sourceType = DocumentSourceType.require(request.sourceType());
        DocumentEntity document = createDocument(tid, kid, docId, request.displayName().strip(), sourceType);
        DocumentVersionEntity draft = newVersionEntity(tid, kid, docId, newVersionKey(tid, kid, docId));
        draft.setStatus("draft");
        draft.setParsedMarkdown(sourceType.placeholder());
        documentVersionRepository.save(draft);
        log.info("[RAG] document created: tenant={}, kb={}, doc={}", tid, kid, docId);
        return toDetail(document);
    }

    @Transactional
    public DocumentDetail updateDocument(String tenantId, String kbId, String docId, UpdateDocumentRequest request) {
        if (request == null || !StringUtils.hasText(request.displayName())) {
            throw new BizException(RagErrorCode.DOC_ID_DISPLAY_NAME_REQUIRED);
        }
        DocumentEntity document = requireDocument(tenantId, kbId, docId);
        document.setDisplayName(request.displayName().strip());
        document.setUpdatedAt(Instant.now());
        documentRepository.save(document);
        return toDetail(document);
    }

    @Transactional
    public void deleteDocument(String tenantId, String kbId, String docId) {
        DocumentEntity document = requireDocument(tenantId, kbId, docId);
        String tid = document.getTenantId();
        String kid = document.getKbId();
        List<DocumentVersionEntity> versions = documentVersionRepository
                .findByTenantIdAndKbIdAndDocIdOrderByVersionDesc(tid, kid, docId);
        for (DocumentVersionEntity version : versions) {
            purgeVersionStorage(document, version);
            if ("active".equals(version.getStatus())) {
                milvusService.deleteByDocVersion(tid, kid, docId, version.getVersion());
                elasticsearchIndexService.deleteByDocVersion(tid, kid, docId, version.getVersion());
            }
        }
        documentVersionRepository.deleteAll(versions);
        documentRepository.delete(document);
        log.info("[RAG] document deleted: tenant={}, kb={}, doc={}", tid, kid, docId);
    }

    public DocumentDetail getDocument(String tenantId, String kbId, String docId) {
        return toDetail(requireDocument(tenantId, kbId, docId));
    }

    public List<ChunkPreviewDto> listChunks(
            String tenantId, String kbId, String docId, String version, String store) {
        DocumentEntity doc = requireDocument(tenantId, kbId, docId);
        String ver = resolveVersion(doc, version);
        if ("es".equalsIgnoreCase(store)) {
            return elasticsearchIndexService.queryChunksByDocVersion(
                            doc.getTenantId(), doc.getKbId(), doc.getDocId(), ver).stream()
                    .map(row -> new ChunkPreviewDto(
                            row.get("chunk_index") instanceof Number n ? n.intValue() : 0,
                            row.get("doc_name") != null ? row.get("doc_name").toString() : doc.getDocId(),
                            row.get("content") != null ? row.get("content").toString() : ""))
                    .sorted(Comparator.comparingInt(ChunkPreviewDto::chunkIndex))
                    .toList();
        }
        return milvusService.queryChunks(doc.getTenantId(), doc.getKbId(), doc.getDocId(), ver).stream()
                .map(c -> new ChunkPreviewDto(c.chunkIndex(), c.docName(), c.content()))
                .toList();
    }

    public DocumentContentView getVersionContent(String tenantId, String kbId, String docId, String version) {
        DocumentEntity doc = requireDocument(tenantId, kbId, docId);
        DocumentVersionEntity ver = requireVersion(doc, version);
        String content = readVersionContent(doc, ver);
        return new DocumentContentView(version, content, ver.getStoragePath());
    }

    @Transactional
    public DocumentContentView saveDraftContent(
            String tenantId, String kbId, String docId, String version, SaveDocumentContentRequest request) {
        if (request == null || !StringUtils.hasText(request.content())) {
            throw new BizException(RagErrorCode.CONTENT_EMPTY);
        }
        DocumentEntity doc = requireDocument(tenantId, kbId, docId);
        DocumentSourceType sourceType = DocumentSourceType.require(doc.getSourceType());
        if (!sourceType.inlineEditable()) {
            throw new BizException(RagErrorCode.VERSION_NOT_EDITABLE);
        }
        DocumentVersionEntity ver = requireDraftVersion(doc, version);
        persistDraftContent(doc, ver, request.content());
        return new DocumentContentView(version, request.content(), ver.getStoragePath());
    }

    public DocumentContentView uploadDocumentFileSync(
            String tenantId, String kbId, String docId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(RagErrorCode.CONTENT_EMPTY);
        }
        DocumentEntity doc = requireDocument(tenantId, kbId, docId);
        DocumentSourceType sourceType = DocumentSourceType.require(doc.getSourceType());
        String content = documentFileParser.parse(sourceType, file);
        return self.persistUploadedContent(tenantId, kbId, docId, content);
    }

    @Transactional
    public String prepareAsyncUploadDraft(
            String tenantId, String kbId, String docId, Long jobId, String placeholder) {
        DocumentEntity doc = requireDocument(tenantId, kbId, docId);
        DocumentVersionEntity target = resolveUploadTarget(doc);
        target.setIngestJobId(jobId);
        target.setParsedMarkdown(placeholder);
        documentVersionRepository.save(target);
        doc.setUpdatedAt(Instant.now());
        documentRepository.save(doc);
        return target.getVersion();
    }

    @Transactional
    public void finishAsyncUpload(
            String tenantId, String kbId, String docId, String version, String content) {
        DocumentEntity doc = requireDocument(tenantId, kbId, docId);
        DocumentVersionEntity ver = requireVersion(doc, version);
        persistDraftContent(doc, ver, content);
    }

    public DocumentEntity requireDocumentPublic(String tenantId, String kbId, String docId) {
        return requireDocument(tenantId, kbId, docId);
    }

    @Transactional
    public DocumentContentView persistUploadedContent(
            String tenantId, String kbId, String docId, String content) {
        DocumentEntity doc = requireDocument(tenantId, kbId, docId);
        DocumentVersionEntity target = resolveUploadTarget(doc);
        persistDraftContent(doc, target, content);
        return new DocumentContentView(target.getVersion(), content, target.getStoragePath());
    }

    @Transactional
    public Mono<IngestResult> publishVersion(String tenantId, String kbId, String docId, String version) {
        DocumentEntity doc = requireDocument(tenantId, kbId, docId);
        DocumentSourceType sourceType = DocumentSourceType.require(doc.getSourceType());
        DocumentVersionEntity ver = requireVersion(doc, version);
        if (!"draft".equals(ver.getStatus())) {
            throw new BizException(RagErrorCode.VERSION_NOT_EDITABLE);
        }
        ensureParseFinished(ver);
        ensureQuarantineConfirmed(ver);
        String content = desensitizeClient.scrubForPublish(readVersionContent(doc, ver));
        if (!StringUtils.hasText(content) || sourceType.isPlaceholder(content)) {
            throw new BizException(RagErrorCode.VERSION_NO_CONTENT);
        }
        String docName = doc.getDisplayName();
        String tid = doc.getTenantId();
        String kid = doc.getKbId();
        supersedeActiveVersions(tid, kid, docId);
        int chunkMaxSize = effectiveConfigResolver.resolve(tid, kid).chunkMaxSize();
        List<String> chunks = markdownParser.parse(content, chunkMaxSize);
        ver.setStatus("active");
        ver.setChunkCount(chunks.size());
        ver.setPublishedAt(Instant.now());
        ver.setParsedMarkdown(content);
        documentVersionRepository.save(ver);
        doc.setActiveVersion(version);
        doc.setUpdatedAt(Instant.now());
        documentRepository.save(doc);
        markIngestJobActive(ver);
        log.info("[RAG] document published: tenant={}, kb={}, doc={}, v={}, chunks={}",
                tid, kid, docId, version, chunks.size());
        return embedAndIndex(tid, kid, docId, docName, version, chunks)
                .thenReturn(new IngestResult(docId, docName, version, chunks.size()));
    }

    @Transactional
    public DocumentDetail forkVersion(String tenantId, String kbId, String docId, String sourceVersion) {
        DocumentEntity doc = requireDocument(tenantId, kbId, docId);
        DocumentVersionEntity source = requireVersion(doc, sourceVersion);
        if (!hasVersionContent(doc, source)) {
            throw new BizException(RagErrorCode.SOURCE_CONTENT_MISSING);
        }
        Optional<DocumentVersionEntity> draft = findContentDraft(doc);
        DocumentVersionEntity target;
        String targetVersion;
        if (draft.isPresent()) {
            if (isEmptyDraft(draft.get(), doc)) {
                target = draft.get();
                targetVersion = target.getVersion();
                purgeVersionStorage(doc, target);
            } else {
                throw new BizException(RagErrorCode.DRAFT_ALREADY_EXISTS);
            }
        } else {
            targetVersion = newVersionKey(doc.getTenantId(), doc.getKbId(), docId);
            target = newVersionEntity(doc.getTenantId(), doc.getKbId(), docId, targetVersion);
        }
        String content = readVersionContent(doc, source);
        target.setStatus("draft");
        persistDraftContent(doc, target, content);
        doc.setUpdatedAt(Instant.now());
        documentRepository.save(doc);
        log.info("[RAG] document forked: doc={}, from v{} to draft v{}", docId, sourceVersion, targetVersion);
        return toDetail(doc);
    }

    @Transactional
    public Mono<IngestResult> ingestText(String tenantId, String kbId, IngestTextRequest request) {
        if (request == null || !StringUtils.hasText(request.content())) {
            throw new BizException(RagErrorCode.CONTENT_EMPTY);
        }
        knowledgeBaseService.requireKb(tenantId, kbId);
        String tid = KnowledgeBaseService.normalizeTenant(tenantId);
        String kid = KnowledgeBaseService.requireKbId(kbId);
        String content = desensitizeClient.scrubForPublish(request.content());
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
        String newVersion = newVersionKey(tid, kid, docId);
        int chunkMaxSize = effectiveConfigResolver.resolve(tid, kid).chunkMaxSize();
        List<String> chunks = markdownParser.parse(content, chunkMaxSize);
        String storagePath = ragStorageFacade.documentContentRef(tid, kid, docId, newVersion);
        ragStorageFacade.putDocumentMarkdown(tid, kid, docId, newVersion, content);
        DocumentVersionEntity versionEntity = newVersionEntity(tid, kid, docId, newVersion);
        versionEntity.setStatus("active");
        versionEntity.setParsedMarkdown(content);
        versionEntity.setStoragePath(storagePath);
        versionEntity.setChunkCount(chunks.size());
        versionEntity.setPublishedAt(Instant.now());
        documentVersionRepository.save(versionEntity);
        document.setActiveVersion(newVersion);
        document.setUpdatedAt(Instant.now());
        documentRepository.save(document);
        log.info("[RAG] catalog ingest: tenant={}, kb={}, doc={}, v={}, chunks={}",
                tid, kid, docId, newVersion, chunks.size());
        return embedAndIndex(tid, kid, docId, docName, newVersion, chunks)
                .thenReturn(new IngestResult(docId, docName, newVersion, chunks.size()));
    }

    /** 兼容 POST /api/rag/documents */
    public Mono<Map<String, Object>> ingestLegacy(String tenantId, Map<String, String> body) {
        IngestTextRequest request = new IngestTextRequest(
                body.get("content"),
                firstNonBlank(body.get("docId"), body.get("doc_id")),
                body.get("docName") != null ? body.get("docName") : body.get("title"),
                body.get("displayName") != null ? body.get("displayName") : body.get("docName"));
        return ingestText(tenantId, "default", request)
                .map(r -> Map.<String, Object>of("docName", r.docName(), "chunks", r.chunks()));
    }

    @Transactional
    public void supersedeVersion(String tenantId, String kbId, String docId, String version) {
        DocumentEntity doc = requireDocument(tenantId, kbId, docId);
        DocumentVersionEntity entity = requireVersion(doc, version);
        markSuperseded(entity);
        if (StringUtils.hasText(doc.getActiveVersion()) && doc.getActiveVersion().equals(version)) {
            doc.setActiveVersion(null);
            doc.setUpdatedAt(Instant.now());
            documentRepository.save(doc);
        }
    }

    private Mono<Void> embedAndIndex(
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

    private void persistDraftContent(DocumentEntity doc, DocumentVersionEntity ver, String content) {
        String tid = doc.getTenantId();
        String kid = doc.getKbId();
        String docId = doc.getDocId();
        String version = ver.getVersion();
        if (StringUtils.hasText(ver.getStoragePath())) {
            ragStorageFacade.deleteDocumentMarkdown(tid, kid, docId, version, ver.getStoragePath());
        }
        String storagePath = ragStorageFacade.documentContentRef(tid, kid, docId, version);
        ragStorageFacade.putDocumentMarkdown(tid, kid, docId, version, content);
        ver.setStoragePath(storagePath);
        ver.setParsedMarkdown(content);
        ver.setStatus("draft");
        ver.setChunkCount(0);
        ver.setPublishedAt(null);
        documentVersionRepository.save(ver);
        doc.setUpdatedAt(Instant.now());
        documentRepository.save(doc);
    }

    private DocumentVersionEntity resolveUploadTarget(DocumentEntity doc) {
        String tid = doc.getTenantId();
        String kid = doc.getKbId();
        String docId = doc.getDocId();
        Optional<DocumentVersionEntity> latest = documentVersionRepository
                .findByTenantIdAndKbIdAndDocIdOrderByVersionDesc(tid, kid, docId).stream().findFirst();
        Optional<DocumentVersionEntity> draft = findDraftVersion(doc);
        if (draft.isPresent()) {
            DocumentVersionEntity existing = draft.get();
            if (isEmptyDraft(existing, doc)) {
                return existing;
            }
            if (latest.isPresent() && existing.getVersion().equals(latest.get().getVersion())) {
                purgeVersionStorage(doc, existing);
                return existing;
            }
            throw new BizException(RagErrorCode.DRAFT_ALREADY_EXISTS);
        }
        String targetVersion = newVersionKey(tid, kid, docId);
        DocumentVersionEntity created = newVersionEntity(tid, kid, docId, targetVersion);
        created.setStatus("draft");
        return created;
    }

    private String readVersionContent(DocumentEntity doc, DocumentVersionEntity ver) {
        if (StringUtils.hasText(ver.getStoragePath())) {
            try {
                return ragStorageFacade.readDocumentMarkdown(
                        doc.getTenantId(), doc.getKbId(), doc.getDocId(), ver.getVersion(), ver.getStoragePath());
            } catch (Exception e) {
                log.warn("[RAG] read storage failed doc={} v={}: {}", doc.getDocId(), ver.getVersion(), e.getMessage());
            }
        }
        if (StringUtils.hasText(ver.getParsedMarkdown())) {
            return ver.getParsedMarkdown();
        }
        throw new BizException(RagErrorCode.SOURCE_CONTENT_MISSING);
    }

    private void purgeVersionStorage(DocumentEntity doc, DocumentVersionEntity ver) {
        if (StringUtils.hasText(ver.getStoragePath())) {
            ragStorageFacade.deleteDocumentMarkdown(
                    doc.getTenantId(), doc.getKbId(), doc.getDocId(), ver.getVersion(), ver.getStoragePath());
        }
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

    private DocumentDetail toDetail(DocumentEntity doc) {
        List<DocumentVersionSummary> versions = documentVersionRepository
                .findByTenantIdAndKbIdAndDocIdOrderByVersionDesc(doc.getTenantId(), doc.getKbId(), doc.getDocId())
                .stream()
                .map(v -> new DocumentVersionSummary(
                        v.getVersion(),
                        v.getStatus(),
                        v.getChunkCount(),
                        hasVersionContent(doc, v),
                        needsQuarantineConfirm(v),
                        quarantineJobId(v),
                        v.getPublishedAt() != null ? v.getPublishedAt().toString() : null,
                        v.getCreatedAt() != null ? v.getCreatedAt().toString() : null))
                .toList();
        return new DocumentDetail(
                doc.getDocId(), doc.getDisplayName(), doc.getSourceType(), doc.getActiveVersion(), versions);
    }

    private DocumentEntity createDocument(
            String tenantId, String kbId, String docId, String displayName, DocumentSourceType sourceType) {
        DocumentEntity entity = new DocumentEntity();
        entity.setTenantId(tenantId);
        entity.setKbId(kbId);
        entity.setDocId(docId);
        entity.setDisplayName(displayName);
        entity.setSourceType(sourceType.wire());
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        return documentRepository.save(entity);
    }

    private DocumentEntity createDocument(String tenantId, String kbId, String docId, String displayName) {
        return createDocument(tenantId, kbId, docId, displayName, DocumentSourceType.MARKDOWN);
    }

    private DocumentVersionEntity newVersionEntity(String tenantId, String kbId, String docId, String version) {
        DocumentVersionEntity entity = new DocumentVersionEntity();
        entity.setTenantId(tenantId);
        entity.setKbId(kbId);
        entity.setDocId(docId);
        entity.setVersion(version);
        entity.setStatus("draft");
        entity.setChunkCount(0);
        entity.setCreatedAt(DocumentVersionTime.toInstant(version));
        return entity;
    }

    private String newVersionKey(String tenantId, String kbId, String docId) {
        List<String> existing = documentVersionRepository
                .findByTenantIdAndKbIdAndDocIdOrderByVersionDesc(tenantId, kbId, docId)
                .stream()
                .map(DocumentVersionEntity::getVersion)
                .toList();
        return DocumentVersionTime.uniqueKey(existing);
    }

    private void ensureParseFinished(DocumentVersionEntity ver) {
        if (ver.getIngestJobId() == null) {
            return;
        }
        ingestJobRepository.findById(ver.getIngestJobId()).ifPresent(job -> {
            if ("failed".equals(job.getStatus())) {
                throw new BizException(RagErrorCode.INGEST_PARSE_FAILED);
            }
            if ("parsing".equals(job.getStatus()) || "queued".equals(job.getStatus())) {
                throw new BizException(RagErrorCode.DOCUMENT_PARSE_IN_PROGRESS);
            }
        });
    }

    private void ensureQuarantineConfirmed(DocumentVersionEntity ver) {
        if (ver.getIngestJobId() == null) {
            return;
        }
        ingestJobRepository.findById(ver.getIngestJobId()).ifPresent(job -> {
            if ("quarantine".equals(job.getStatus()) && !job.isAutoPass()) {
                throw new BizException(RagErrorCode.INGEST_QUARANTINE_PENDING);
            }
        });
    }

    private boolean needsQuarantineConfirm(DocumentVersionEntity ver) {
        if (ver.getIngestJobId() == null) {
            return false;
        }
        return ingestJobRepository.findById(ver.getIngestJobId())
                .map(job -> "quarantine".equals(job.getStatus()) && !job.isAutoPass())
                .orElse(false);
    }

    private Long quarantineJobId(DocumentVersionEntity ver) {
        if (!needsQuarantineConfirm(ver)) {
            return null;
        }
        return ver.getIngestJobId();
    }

    private void markIngestJobActive(DocumentVersionEntity ver) {
        if (ver.getIngestJobId() == null) {
            return;
        }
        ingestJobRepository.findById(ver.getIngestJobId()).ifPresent(job -> {
            job.setStatus("active");
            job.setUpdatedAt(Instant.now());
            ingestJobRepository.save(job);
        });
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

    private DocumentVersionEntity requireVersion(DocumentEntity doc, String version) {
        return documentVersionRepository
                .findByTenantIdAndKbIdAndDocIdAndVersion(
                        doc.getTenantId(), doc.getKbId(), doc.getDocId(), version)
                .orElseThrow(() -> new BizException(RagErrorCode.VERSION_NOT_FOUND));
    }

    private DocumentVersionEntity requireDraftVersion(DocumentEntity doc, String version) {
        DocumentVersionEntity ver = requireVersion(doc, version);
        if (!"draft".equals(ver.getStatus())) {
            throw new BizException(RagErrorCode.VERSION_NOT_EDITABLE);
        }
        return ver;
    }

    private Optional<DocumentVersionEntity> findDraftVersion(DocumentEntity doc) {
        return documentVersionRepository
                .findFirstByTenantIdAndKbIdAndDocIdAndStatusOrderByVersionDesc(
                        doc.getTenantId(), doc.getKbId(), doc.getDocId(), "draft");
    }

    private Optional<DocumentVersionEntity> findContentDraft(DocumentEntity doc) {
        return findDraftVersion(doc).filter(v -> hasVersionContent(doc, v));
    }

    private boolean isEmptyDraft(DocumentVersionEntity ver, DocumentEntity doc) {
        return !hasVersionContent(doc, ver);
    }

    private boolean hasVersionContent(DocumentEntity doc, DocumentVersionEntity ver) {
        DocumentSourceType sourceType = DocumentSourceType.require(doc.getSourceType());
        if (StringUtils.hasText(ver.getStoragePath())) {
            return true;
        }
        return StringUtils.hasText(ver.getParsedMarkdown())
                && !sourceType.isPlaceholder(ver.getParsedMarkdown().strip())
                && !DocumentParseJobService.PARSING_PLACEHOLDER.equals(ver.getParsedMarkdown().strip());
    }

    private boolean hasVersionContent(DocumentVersionEntity ver) {
        return StringUtils.hasText(ver.getStoragePath())
                || (StringUtils.hasText(ver.getParsedMarkdown())
                && !DocumentSourceType.MARKDOWN.isPlaceholder(ver.getParsedMarkdown().strip())
                && !DocumentSourceType.TEXT.isPlaceholder(ver.getParsedMarkdown().strip())
                && !DocumentSourceType.PDF.isPlaceholder(ver.getParsedMarkdown().strip())
                && !DocumentSourceType.DOCX.isPlaceholder(ver.getParsedMarkdown().strip())
                && !DocumentParseJobService.PARSING_PLACEHOLDER.equals(ver.getParsedMarkdown().strip()));
    }

    private String resolveVersion(DocumentEntity doc, String version) {
        if (StringUtils.hasText(version)) {
            return version.strip();
        }
        if (StringUtils.hasText(doc.getActiveVersion())) {
            return doc.getActiveVersion();
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

    private static String firstNonBlank(String primary, String fallback) {
        if (StringUtils.hasText(primary)) {
            return primary.strip();
        }
        if (StringUtils.hasText(fallback)) {
            return fallback.strip();
        }
        return null;
    }
}
