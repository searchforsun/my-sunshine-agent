package com.sunshine.rag.admin.catalog;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.rag.admin.catalog.dto.DocumentDetail;
import com.sunshine.rag.admin.catalog.dto.DocumentVersionSummary;
import com.sunshine.rag.entity.DocumentEntity;
import com.sunshine.rag.entity.DocumentVersionEntity;
import com.sunshine.rag.exception.RagErrorCode;
import com.sunshine.rag.repository.DocumentRepository;
import com.sunshine.rag.repository.DocumentVersionRepository;
import com.sunshine.rag.repository.IngestJobRepository;
import com.sunshine.rag.storage.RagStorageFacade;
import com.sunshine.rag.util.DocumentVersionTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 文档版本生命周期、草稿存储与详情组装 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentVersionOps {

    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final IngestJobRepository ingestJobRepository;
    private final RagStorageFacade ragStorageFacade;
    private final DocumentChunkIndexer chunkIndexer;

    public DocumentDetail toDetail(DocumentEntity doc) {
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
                        v.getChunkStrategy(),
                        v.getPublishedAt() != null ? v.getPublishedAt().toString() : null,
                        v.getCreatedAt() != null ? v.getCreatedAt().toString() : null))
                .toList();
        return new DocumentDetail(
                doc.getDocId(), doc.getDisplayName(), doc.getSourceType(), doc.getActiveVersion(), versions);
    }

    public void persistDraftContent(DocumentEntity doc, DocumentVersionEntity ver, String content) {
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

    public String readVersionContent(DocumentEntity doc, DocumentVersionEntity ver) {
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

    public void purgeVersionStorage(DocumentEntity doc, DocumentVersionEntity ver) {
        if (StringUtils.hasText(ver.getStoragePath())) {
            ragStorageFacade.deleteDocumentMarkdown(
                    doc.getTenantId(), doc.getKbId(), doc.getDocId(), ver.getVersion(), ver.getStoragePath());
        }
    }

    public DocumentVersionEntity resolveUploadTarget(DocumentEntity doc) {
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

    public void supersedeActiveVersions(String tenantId, String kbId, String docId) {
        List<DocumentVersionEntity> active = documentVersionRepository
                .findByTenantIdAndKbIdAndDocIdAndStatus(tenantId, kbId, docId, "active");
        for (DocumentVersionEntity v : active) {
            markSuperseded(v);
        }
    }

    public void markSuperseded(DocumentVersionEntity entity) {
        if ("superseded".equals(entity.getStatus())) {
            return;
        }
        chunkIndexer.purgeIndexedVersion(
                entity.getTenantId(), entity.getKbId(), entity.getDocId(), entity.getVersion());
        entity.setStatus("superseded");
        documentVersionRepository.save(entity);
    }

    public DocumentVersionEntity newVersionEntity(String tenantId, String kbId, String docId, String version) {
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

    public String newVersionKey(String tenantId, String kbId, String docId) {
        List<String> existing = documentVersionRepository
                .findByTenantIdAndKbIdAndDocIdOrderByVersionDesc(tenantId, kbId, docId)
                .stream()
                .map(DocumentVersionEntity::getVersion)
                .toList();
        return DocumentVersionTime.uniqueKey(existing);
    }

    public void ensureParseFinished(DocumentVersionEntity ver) {
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

    public void ensureQuarantineConfirmed(DocumentVersionEntity ver) {
        if (ver.getIngestJobId() == null) {
            return;
        }
        ingestJobRepository.findById(ver.getIngestJobId()).ifPresent(job -> {
            if ("quarantine".equals(job.getStatus()) && !job.isAutoPass()) {
                throw new BizException(RagErrorCode.INGEST_QUARANTINE_PENDING);
            }
        });
    }

    public void markIngestJobActive(DocumentVersionEntity ver) {
        if (ver.getIngestJobId() == null) {
            return;
        }
        ingestJobRepository.findById(ver.getIngestJobId()).ifPresent(job -> {
            job.setStatus("active");
            job.setUpdatedAt(Instant.now());
            ingestJobRepository.save(job);
        });
    }

    public DocumentVersionEntity requireVersion(DocumentEntity doc, String version) {
        return documentVersionRepository
                .findByTenantIdAndKbIdAndDocIdAndVersion(
                        doc.getTenantId(), doc.getKbId(), doc.getDocId(), version)
                .orElseThrow(() -> new BizException(RagErrorCode.VERSION_NOT_FOUND));
    }

    public DocumentVersionEntity requireDraftVersion(DocumentEntity doc, String version) {
        DocumentVersionEntity ver = requireVersion(doc, version);
        if (!"draft".equals(ver.getStatus())) {
            throw new BizException(RagErrorCode.VERSION_NOT_EDITABLE);
        }
        return ver;
    }

    public Optional<DocumentVersionEntity> findDraftVersion(DocumentEntity doc) {
        return documentVersionRepository
                .findFirstByTenantIdAndKbIdAndDocIdAndStatusOrderByVersionDesc(
                        doc.getTenantId(), doc.getKbId(), doc.getDocId(), "draft");
    }

    public Optional<DocumentVersionEntity> findContentDraft(DocumentEntity doc) {
        return findDraftVersion(doc).filter(v -> hasVersionContent(doc, v));
    }

    public boolean isEmptyDraft(DocumentVersionEntity ver, DocumentEntity doc) {
        return !hasVersionContent(doc, ver);
    }

    public boolean hasVersionContent(DocumentEntity doc, DocumentVersionEntity ver) {
        DocumentSourceType sourceType = DocumentSourceType.require(doc.getSourceType());
        if (StringUtils.hasText(ver.getStoragePath())) {
            return true;
        }
        return StringUtils.hasText(ver.getParsedMarkdown())
                && !sourceType.isPlaceholder(ver.getParsedMarkdown().strip())
                && !DocumentParseJobService.PARSING_PLACEHOLDER.equals(ver.getParsedMarkdown().strip());
    }

    public String resolveVersion(DocumentEntity doc, String version) {
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
}
