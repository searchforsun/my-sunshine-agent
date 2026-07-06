package com.sunshine.rag.admin.catalog;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.rag.admin.catalog.dto.DocumentParseJobStatus;
import com.sunshine.rag.admin.catalog.dto.DocumentUploadResponse;
import com.sunshine.rag.admin.catalog.parser.DocumentFileParser;
import com.sunshine.rag.admin.catalog.parser.ParseProgressListener;
import com.sunshine.rag.entity.DocumentEntity;
import com.sunshine.rag.entity.IngestJobEntity;
import com.sunshine.rag.exception.RagErrorCode;
import com.sunshine.rag.repository.IngestJobRepository;
import com.sunshine.rag.admin.catalog.parser.ParseConfidenceEstimator;
import com.sunshine.rag.config.RagIngestProperties;
import com.sunshine.rag.storage.RagStorageFacade;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;

/** PDF/DOCX 异步解析任务 */
@Slf4j
@Service
public class DocumentParseJobService {

    public static final String PARSING_PLACEHOLDER = "解析中，请稍候…";

    private final DocumentCatalogService documentCatalogService;
    private final DocumentFileParser documentFileParser;
    private final IngestJobRepository ingestJobRepository;
    private final RagStorageFacade ragStorageFacade;
    private final DocumentParseAsyncRunner asyncRunner;
    private final RagIngestProperties ingestProperties;

    @Lazy
    @Autowired
    DocumentParseJobService self;

    public DocumentParseJobService(
            DocumentCatalogService documentCatalogService,
            DocumentFileParser documentFileParser,
            IngestJobRepository ingestJobRepository,
            RagStorageFacade ragStorageFacade,
            RagIngestProperties ingestProperties,
            @Lazy DocumentParseAsyncRunner asyncRunner) {
        this.documentCatalogService = documentCatalogService;
        this.documentFileParser = documentFileParser;
        this.ingestJobRepository = ingestJobRepository;
        this.ragStorageFacade = ragStorageFacade;
        this.ingestProperties = ingestProperties;
        this.asyncRunner = asyncRunner;
    }

    public DocumentUploadResponse upload(
            String tenantId, String kbId, String docId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(RagErrorCode.CONTENT_EMPTY);
        }
        DocumentEntity doc = documentCatalogService.requireDocumentPublic(tenantId, kbId, docId);
        DocumentSourceType sourceType = DocumentSourceType.require(doc.getSourceType());
        sourceType.validateUploadFileName(file.getOriginalFilename());
        if (documentFileParser.isAsyncSourceType(sourceType)) {
            return submitAsync(tenantId, kbId, docId, sourceType, file);
        }
        var view = documentCatalogService.uploadDocumentFileSync(tenantId, kbId, docId, file);
        return new DocumentUploadResponse(false, null, view.version(), "done", 100.0, view.content(), view.storagePath());
    }

    public DocumentParseJobStatus getJob(String tenantId, String kbId, long jobId) {
        IngestJobEntity job = requireJob(tenantId, kbId, jobId);
        return toStatus(job);
    }

    @Transactional
    public void confirmJob(String tenantId, String kbId, long jobId) {
        IngestJobEntity job = requireJob(tenantId, kbId, jobId);
        if (!"quarantine".equals(job.getStatus())) {
            throw new BizException(RagErrorCode.INGEST_INVALID_STATUS);
        }
        job.setAutoPass(true);
        job.setStatus("preview");
        job.setUpdatedAt(Instant.now());
        ingestJobRepository.save(job);
        log.info("[RAG] 解析 quarantine 已确认 job={} doc={}", jobId, job.getDocId());
    }

    public void executeJob(long jobId) {
        IngestJobEntity job = ingestJobRepository.findById(jobId)
                .orElseThrow(() -> new BizException(RagErrorCode.INGEST_JOB_NOT_FOUND));
        if (isTerminal(job.getStatus())) {
            return;
        }
        try {
            self.markParsing(jobId);
            byte[] bytes = ragStorageFacade.readParseSource(job.getSourceObjectKey());
            DocumentSourceType sourceType = DocumentSourceType.require(job.getSourceType());
            ParseProgressListener progress = (page, total, pct) -> self.updateProgress(jobId, page, total, pct);
            String content = documentFileParser.parseBytes(sourceType, bytes, job.getFileName(), progress);
            documentCatalogService.finishAsyncUpload(
                    job.getTenantId(), job.getKbId(), job.getDocId(), job.getTargetVersion(), content);
            self.markDone(jobId, content, sourceType);
            ragStorageFacade.deleteParseSource(job.getSourceObjectKey());
            log.info("[RAG] 异步解析完成 job={} doc={} v={}", jobId, job.getDocId(), job.getTargetVersion());
        } catch (Exception e) {
            log.warn("[RAG] 异步解析失败 job={}: {}", jobId, e.getMessage());
            self.markFailed(jobId, e.getMessage());
        }
    }

    private DocumentUploadResponse submitAsync(
            String tenantId, String kbId, String docId, DocumentSourceType sourceType, MultipartFile file) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new BizException(RagErrorCode.INGEST_PARSE_FAILED);
        }
        String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename().strip() : "upload.bin";
        IngestJobEntity job = self.createJob(tenantId, kbId, docId, sourceType, fileName, file.getContentType());
        String objectKey = ragStorageFacade.parseSourceObjectKey(tenantId, kbId, docId, job.getId(), fileName);
        ragStorageFacade.putParseSource(objectKey, bytes, file.getContentType() != null ? file.getContentType() : "application/octet-stream");
        job.setSourceObjectKey(objectKey);
        ingestJobRepository.save(job);
        String version = documentCatalogService.prepareAsyncUploadDraft(
                tenantId, kbId, docId, job.getId(), PARSING_PLACEHOLDER);
        job.setTargetVersion(version);
        ingestJobRepository.save(job);
        asyncRunner.run(job.getId());
        return new DocumentUploadResponse(true, job.getId(), version, "parsing", 0.0, null, null);
    }

    @Transactional
    public IngestJobEntity createJob(
            String tenantId, String kbId, String docId, DocumentSourceType sourceType,
            String fileName, String mimeType) {
        IngestJobEntity job = new IngestJobEntity();
        job.setTenantId(tenantId);
        job.setKbId(kbId);
        job.setDocId(docId);
        job.setSourceType(sourceType.wire());
        job.setFileName(fileName);
        job.setMimeType(mimeType);
        job.setStatus("queued");
        job.setProgressPct(0.0);
        job.setCreatedAt(Instant.now());
        job.setUpdatedAt(Instant.now());
        return ingestJobRepository.save(job);
    }

    @Transactional
    public void markParsing(long jobId) {
        IngestJobEntity job = ingestJobRepository.findById(jobId)
                .orElseThrow(() -> new BizException(RagErrorCode.INGEST_JOB_NOT_FOUND));
        job.setStatus("parsing");
        job.setUpdatedAt(Instant.now());
        ingestJobRepository.save(job);
    }

    @Transactional
    public void updateProgress(long jobId, int page, int total, double pct) {
        ingestJobRepository.findById(jobId).ifPresent(job -> {
            job.setProgressPage(page);
            job.setTotalPages(total);
            job.setProgressPct(Math.min(99.0, pct));
            job.setUpdatedAt(Instant.now());
            ingestJobRepository.save(job);
        });
    }

    @Transactional
    public void markDone(long jobId, String content, DocumentSourceType sourceType) {
        IngestJobEntity job = ingestJobRepository.findById(jobId)
                .orElseThrow(() -> new BizException(RagErrorCode.INGEST_JOB_NOT_FOUND));
        ParseConfidenceEstimator.Result confidence = ParseConfidenceEstimator.estimate(
                content, sourceType, ingestProperties.getConfidenceThreshold());
        job.setConfidence(confidence.confidence());
        job.setParsedMarkdown(content);
        job.setProgressPct(100.0);
        job.setUpdatedAt(Instant.now());
        if (ingestProperties.isQuarantineEnabled() && !confidence.autoPass()) {
            job.setAutoPass(false);
            job.setStatus("quarantine");
        } else {
            job.setAutoPass(true);
            job.setStatus("preview");
        }
        ingestJobRepository.save(job);
    }

    @Transactional
    public void markFailed(long jobId, String message) {
        ingestJobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus("failed");
            job.setErrorMsg(truncate(message));
            job.setUpdatedAt(Instant.now());
            ingestJobRepository.save(job);
        });
    }

    private IngestJobEntity requireJob(String tenantId, String kbId, long jobId) {
        return ingestJobRepository.findByIdAndTenantIdAndKbId(jobId, tenantId, kbId)
                .orElseThrow(() -> new BizException(RagErrorCode.INGEST_JOB_NOT_FOUND));
    }

    private static DocumentParseJobStatus toStatus(IngestJobEntity job) {
        boolean needsConfirm = "quarantine".equals(job.getStatus()) && !job.isAutoPass();
        return new DocumentParseJobStatus(
                job.getId(),
                job.getDocId(),
                job.getTargetVersion(),
                job.getStatus(),
                job.getProgressPct(),
                job.getProgressPage(),
                job.getTotalPages(),
                job.getConfidence(),
                needsConfirm,
                job.getErrorMsg(),
                job.getUpdatedAt());
    }

    private static boolean isTerminal(String status) {
        return "preview".equals(status) || "quarantine".equals(status)
                || "done".equals(status) || "failed".equals(status) || "active".equals(status);
    }

    private static String truncate(String message) {
        if (!StringUtils.hasText(message)) {
            return "解析失败";
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
