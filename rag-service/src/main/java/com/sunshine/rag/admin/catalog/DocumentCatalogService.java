package com.sunshine.rag.admin.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.common.core.exception.BizException;
import com.sunshine.rag.admin.catalog.dto.ChunkPreviewChunkItem;
import com.sunshine.rag.admin.catalog.dto.ChunkPreviewDto;
import com.sunshine.rag.admin.catalog.dto.ChunkPreviewRequest;
import com.sunshine.rag.admin.catalog.dto.ChunkPreviewResponse;
import com.sunshine.rag.admin.catalog.dto.CreateDocumentRequest;
import com.sunshine.rag.admin.catalog.dto.DocumentContentView;
import com.sunshine.rag.admin.catalog.dto.DocumentDetail;
import com.sunshine.rag.admin.catalog.dto.DocumentSummary;
import com.sunshine.rag.admin.catalog.dto.IngestResult;
import com.sunshine.rag.admin.catalog.dto.IngestTextRequest;
import com.sunshine.rag.admin.catalog.dto.SaveDocumentContentRequest;
import com.sunshine.rag.admin.catalog.dto.UpdateDocumentRequest;
import com.sunshine.rag.client.DesensitizeClient;
import com.sunshine.rag.admin.catalog.parser.DocumentFileParser;
import com.sunshine.rag.entity.DocumentEntity;
import com.sunshine.rag.entity.DocumentVersionEntity;
import com.sunshine.rag.chunker.ChunkDraft;
import com.sunshine.rag.chunker.ChunkParams;
import com.sunshine.rag.chunker.ChunkPreviewRecord;
import com.sunshine.rag.chunker.ChunkPreviewService;
import com.sunshine.rag.chunker.ChunkStrategy;
import com.sunshine.rag.exception.RagErrorCode;
import com.sunshine.rag.repository.DocumentRepository;
import com.sunshine.rag.repository.DocumentVersionRepository;
import com.sunshine.rag.storage.RagStorageFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
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
    private final DocumentFileParser documentFileParser;
    private final RagStorageFacade ragStorageFacade;
    private final DesensitizeClient desensitizeClient;
    private final DocumentChunkIndexer chunkIndexer;
    private final DocumentVersionOps versionOps;
    private final ChunkPreviewService chunkPreviewService;
    private final ObjectMapper objectMapper;

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
        DocumentVersionEntity draft = versionOps.newVersionEntity(tid, kid, docId, versionOps.newVersionKey(tid, kid, docId));
        draft.setStatus("draft");
        draft.setParsedMarkdown(sourceType.placeholder());
        documentVersionRepository.save(draft);
        log.info("[RAG] document created: tenant={}, kb={}, doc={}", tid, kid, docId);
        return versionOps.toDetail(document);
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
        return versionOps.toDetail(document);
    }

    @Transactional
    public void deleteDocument(String tenantId, String kbId, String docId) {
        DocumentEntity document = requireDocument(tenantId, kbId, docId);
        String tid = document.getTenantId();
        String kid = document.getKbId();
        List<DocumentVersionEntity> versions = documentVersionRepository
                .findByTenantIdAndKbIdAndDocIdOrderByVersionDesc(tid, kid, docId);
        for (DocumentVersionEntity version : versions) {
            versionOps.purgeVersionStorage(document, version);
            if ("active".equals(version.getStatus())) {
                chunkIndexer.purgeIndexedVersion(tid, kid, docId, version.getVersion());
            }
        }
        documentVersionRepository.deleteAll(versions);
        documentRepository.delete(document);
        log.info("[RAG] document deleted: tenant={}, kb={}, doc={}", tid, kid, docId);
    }

    public DocumentDetail getDocument(String tenantId, String kbId, String docId) {
        return versionOps.toDetail(requireDocument(tenantId, kbId, docId));
    }

    public List<ChunkPreviewDto> listChunks(
            String tenantId, String kbId, String docId, String version, String store) {
        DocumentEntity doc = requireDocument(tenantId, kbId, docId);
        String ver = versionOps.resolveVersion(doc, version);
        return chunkIndexer.listChunks(doc, ver, store);
    }

    public DocumentContentView getVersionContent(String tenantId, String kbId, String docId, String version) {
        DocumentEntity doc = requireDocument(tenantId, kbId, docId);
        DocumentVersionEntity ver = versionOps.requireVersion(doc, version);
        String content = versionOps.readVersionContent(doc, ver);
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
        DocumentVersionEntity ver = versionOps.requireDraftVersion(doc, version);
        versionOps.persistDraftContent(doc, ver, request.content());
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
        DocumentVersionEntity target = versionOps.resolveUploadTarget(doc);
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
        DocumentVersionEntity ver = versionOps.requireVersion(doc, version);
        versionOps.persistDraftContent(doc, ver, content);
    }

    public DocumentEntity requireDocumentPublic(String tenantId, String kbId, String docId) {
        return requireDocument(tenantId, kbId, docId);
    }

    @Transactional
    public DocumentContentView persistUploadedContent(
            String tenantId, String kbId, String docId, String content) {
        DocumentEntity doc = requireDocument(tenantId, kbId, docId);
        DocumentVersionEntity target = versionOps.resolveUploadTarget(doc);
        versionOps.persistDraftContent(doc, target, content);
        return new DocumentContentView(target.getVersion(), content, target.getStoragePath());
    }

    /**
     * 先 embed 成功，再落库 active + 消费 preview。
     * 避免 embedding 失败后出现：库已生效、前端仍草稿、Redis 预览已删。
     */
    public Mono<IngestResult> publishVersion(String tenantId, String kbId, String docId, String previewId) {
        if (!StringUtils.hasText(previewId)) {
            throw new BizException(RagErrorCode.PREVIEW_NOT_FOUND);
        }
        DocumentEntity doc = requireDocument(tenantId, kbId, docId);
        DocumentSourceType sourceType = DocumentSourceType.require(doc.getSourceType());
        String tid = doc.getTenantId();
        String kid = doc.getKbId();
        String strippedPreviewId = previewId.strip();
        ChunkPreviewRecord preview = chunkPreviewService.requirePreview(tid, kid, docId, strippedPreviewId);
        String version = preview.version();
        DocumentVersionEntity ver = versionOps.requireVersion(doc, version);
        if (!"draft".equals(ver.getStatus())) {
            throw new BizException(RagErrorCode.VERSION_NOT_EDITABLE);
        }
        versionOps.ensureParseFinished(ver);
        versionOps.ensureQuarantineConfirmed(ver);
        String content = desensitizeClient.scrubForPublish(versionOps.readVersionContent(doc, ver));
        if (!StringUtils.hasText(content) || sourceType.isPlaceholder(content)) {
            throw new BizException(RagErrorCode.VERSION_NO_CONTENT);
        }
        if (!preview.contentHash().equals(ChunkPreviewService.sha256Hex(content))) {
            throw new BizException(RagErrorCode.PREVIEW_CONTENT_STALE);
        }
        String docName = doc.getDisplayName();
        return chunkIndexer.embedAndIndexDrafts(
                        tid, kid, docId, docName, version, preview.chunks(), preview.strategy())
                .then(Mono.fromCallable(() -> {
                    self.activatePublishedVersion(doc, content, preview, strippedPreviewId);
                    return new IngestResult(docId, docName, version, preview.chunks().size());
                }));
    }

    @Transactional
    public void activatePublishedVersion(
            DocumentEntity doc,
            String content,
            ChunkPreviewRecord preview,
            String previewId) {
        String tid = doc.getTenantId();
        String kid = doc.getKbId();
        String docId = doc.getDocId();
        String version = preview.version();
        DocumentVersionEntity latest = versionOps.requireVersion(doc, version);
        if (!"draft".equals(latest.getStatus())) {
            throw new BizException(RagErrorCode.VERSION_NOT_EDITABLE);
        }
        versionOps.supersedeActiveVersions(tid, kid, docId);
        latest.setStatus("active");
        latest.setChunkCount(preview.chunks().size());
        latest.setChunkStrategy(preview.strategy().wire());
        latest.setChunkParamsJson(writeChunkParamsJson(preview.params()));
        latest.setPublishedAt(Instant.now());
        latest.setParsedMarkdown(content);
        documentVersionRepository.save(latest);
        doc.setActiveVersion(version);
        doc.setUpdatedAt(Instant.now());
        documentRepository.save(doc);
        versionOps.markIngestJobActive(latest);
        chunkPreviewService.consumePreview(tid, kid, docId, previewId);
        log.info("[RAG] document published: tenant={}, kb={}, doc={}, v={}, strategy={}, chunks={}",
                tid, kid, docId, version, preview.strategy().wire(), preview.chunks().size());
    }

    public ChunkPreviewResponse chunkPreview(
            String tenantId, String kbId, String docId, ChunkPreviewRequest request) {
        if (request == null || !StringUtils.hasText(request.strategy())) {
            throw new BizException(RagErrorCode.UNKNOWN_CHUNK_STRATEGY);
        }
        DocumentEntity doc = requireDocument(tenantId, kbId, docId);
        DocumentSourceType sourceType = DocumentSourceType.require(doc.getSourceType());
        DocumentVersionEntity ver = resolvePreviewDraftVersion(doc, request.version());
        versionOps.ensureParseFinished(ver);
        versionOps.ensureQuarantineConfirmed(ver);
        String content = desensitizeClient.scrubForPublish(versionOps.readVersionContent(doc, ver));
        if (!StringUtils.hasText(content) || sourceType.isPlaceholder(content)) {
            throw new BizException(RagErrorCode.VERSION_NO_CONTENT);
        }
        ChunkParams params = resolveChunkParams(request.strategy(), request.params());
        ChunkPreviewRecord record = chunkPreviewService.createPreview(
                doc.getTenantId(),
                doc.getKbId(),
                docId,
                ver.getVersion(),
                content,
                params.strategy(),
                params);
        return toPreviewResponse(record);
    }

    @Transactional
    public DocumentDetail forkVersion(String tenantId, String kbId, String docId, String sourceVersion) {
        DocumentEntity doc = requireDocument(tenantId, kbId, docId);
        DocumentVersionEntity source = versionOps.requireVersion(doc, sourceVersion);
        if (!versionOps.hasVersionContent(doc, source)) {
            throw new BizException(RagErrorCode.SOURCE_CONTENT_MISSING);
        }
        Optional<DocumentVersionEntity> draft = versionOps.findContentDraft(doc);
        DocumentVersionEntity target;
        String targetVersion;
        if (draft.isPresent()) {
            if (versionOps.isEmptyDraft(draft.get(), doc)) {
                target = draft.get();
                targetVersion = target.getVersion();
                versionOps.purgeVersionStorage(doc, target);
            } else {
                throw new BizException(RagErrorCode.DRAFT_ALREADY_EXISTS);
            }
        } else {
            targetVersion = versionOps.newVersionKey(doc.getTenantId(), doc.getKbId(), docId);
            target = versionOps.newVersionEntity(doc.getTenantId(), doc.getKbId(), docId, targetVersion);
        }
        String content = versionOps.readVersionContent(doc, source);
        target.setStatus("draft");
        versionOps.persistDraftContent(doc, target, content);
        doc.setUpdatedAt(Instant.now());
        documentRepository.save(doc);
        log.info("[RAG] document forked: doc={}, from v{} to draft v{}", docId, sourceVersion, targetVersion);
        return versionOps.toDetail(doc);
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
        versionOps.supersedeActiveVersions(tid, kid, docId);
        String newVersion = versionOps.newVersionKey(tid, kid, docId);
        ChunkParams chunkParams = resolveChunkParams(
                StringUtils.hasText(request.strategy()) ? request.strategy() : ChunkStrategy.MARKDOWN.wire(),
                request.params());
        ChunkPreviewRecord preview = chunkPreviewService.createPreview(
                tid, kid, docId, newVersion, content, chunkParams.strategy(), chunkParams);
        List<String> chunks = preview.chunks().stream().map(ChunkDraft::text).toList();
        String storagePath = ragStorageFacade.documentContentRef(tid, kid, docId, newVersion);
        ragStorageFacade.putDocumentMarkdown(tid, kid, docId, newVersion, content);
        DocumentVersionEntity versionEntity = versionOps.newVersionEntity(tid, kid, docId, newVersion);
        versionEntity.setStatus("active");
        versionEntity.setParsedMarkdown(content);
        versionEntity.setStoragePath(storagePath);
        versionEntity.setChunkCount(chunks.size());
        versionEntity.setChunkStrategy(preview.strategy().wire());
        versionEntity.setChunkParamsJson(writeChunkParamsJson(preview.params()));
        versionEntity.setPublishedAt(Instant.now());
        documentVersionRepository.save(versionEntity);
        document.setActiveVersion(newVersion);
        document.setUpdatedAt(Instant.now());
        documentRepository.save(document);
        chunkPreviewService.consumePreview(tid, kid, docId, preview.previewId());
        log.info("[RAG] catalog ingest: tenant={}, kb={}, doc={}, v={}, strategy={}, chunks={}",
                tid, kid, docId, newVersion, preview.strategy().wire(), preview.chunks().size());
        return chunkIndexer.embedAndIndexDrafts(
                        tid, kid, docId, docName, newVersion, preview.chunks(), preview.strategy())
                .thenReturn(new IngestResult(docId, docName, newVersion, preview.chunks().size()));
    }

    @Transactional
    public void supersedeVersion(String tenantId, String kbId, String docId, String version) {
        DocumentEntity doc = requireDocument(tenantId, kbId, docId);
        DocumentVersionEntity entity = versionOps.requireVersion(doc, version);
        versionOps.markSuperseded(entity);
        if (StringUtils.hasText(doc.getActiveVersion()) && doc.getActiveVersion().equals(version)) {
            doc.setActiveVersion(null);
            doc.setUpdatedAt(Instant.now());
            documentRepository.save(doc);
        }
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

    private DocumentVersionEntity resolvePreviewDraftVersion(DocumentEntity doc, String version) {
        if (StringUtils.hasText(version)) {
            return versionOps.requireDraftVersion(doc, version.strip());
        }
        return versionOps.findContentDraft(doc)
                .or(() -> versionOps.findDraftVersion(doc))
                .orElseThrow(() -> new BizException(RagErrorCode.VERSION_NOT_FOUND));
    }

    private ChunkParams resolveChunkParams(String strategyRaw, Map<String, Object> rawParams) {
        ChunkStrategy strategy;
        try {
            strategy = ChunkStrategy.parse(strategyRaw);
        } catch (IllegalArgumentException ex) {
            throw new BizException(RagErrorCode.UNKNOWN_CHUNK_STRATEGY);
        }
        try {
            return ChunkParams.forStrategy(strategy, rawParams);
        } catch (IllegalArgumentException ex) {
            throw new BizException(RagErrorCode.CONFIG_PAYLOAD_INVALID);
        }
    }

    private String writeChunkParamsJson(ChunkParams params) {
        try {
            return objectMapper.writeValueAsString(params.asMap());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("分块参数序列化失败", e);
        }
    }

    private static ChunkPreviewResponse toPreviewResponse(ChunkPreviewRecord record) {
        List<ChunkPreviewChunkItem> chunks = record.chunks().stream()
                .map(draft -> new ChunkPreviewChunkItem(
                        draft.index(), draft.text(), draft.charCount(), draft.meta()))
                .toList();
        return new ChunkPreviewResponse(
                record.previewId(),
                record.strategy().wire(),
                record.params().asMap(),
                record.contentHash(),
                chunks.size(),
                chunks,
                record.expiresAt());
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
