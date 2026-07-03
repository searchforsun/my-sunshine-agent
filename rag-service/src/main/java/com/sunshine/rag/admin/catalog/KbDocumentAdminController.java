package com.sunshine.rag.admin.catalog;

import com.sunshine.common.core.result.R;
import com.sunshine.rag.admin.catalog.dto.ChunkPreviewDto;
import com.sunshine.rag.admin.catalog.dto.CreateDocumentRequest;
import com.sunshine.rag.admin.catalog.dto.DocumentContentView;
import com.sunshine.rag.admin.catalog.dto.DocumentDetail;
import com.sunshine.rag.admin.catalog.dto.DocumentSummary;
import com.sunshine.rag.admin.catalog.dto.IngestResult;
import com.sunshine.rag.admin.catalog.dto.IngestTextRequest;
import com.sunshine.rag.admin.catalog.dto.SaveDocumentContentRequest;
import com.sunshine.rag.admin.catalog.dto.UpdateDocumentRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/rag/admin/kbs/{kbId}")
@RequiredArgsConstructor
public class KbDocumentAdminController {

    private final DocumentCatalogService documentCatalogService;

    @GetMapping("/documents")
    public R<List<DocumentSummary>> listDocuments(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @PathVariable String kbId) {
        return R.ok(documentCatalogService.listDocuments(tenantId, kbId));
    }

    @PostMapping("/documents")
    public R<DocumentDetail> createDocument(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @PathVariable String kbId,
            @RequestBody CreateDocumentRequest request) {
        return R.ok(documentCatalogService.createDocument(tenantId, kbId, request));
    }

    @GetMapping("/documents/{docId}")
    public R<DocumentDetail> getDocument(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @PathVariable String kbId,
            @PathVariable String docId) {
        return R.ok(documentCatalogService.getDocument(tenantId, kbId, docId));
    }

    @PutMapping("/documents/{docId}")
    public R<DocumentDetail> updateDocument(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @PathVariable String kbId,
            @PathVariable String docId,
            @RequestBody UpdateDocumentRequest request) {
        return R.ok(documentCatalogService.updateDocument(tenantId, kbId, docId, request));
    }

    @DeleteMapping("/documents/{docId}")
    public R<Void> deleteDocument(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @PathVariable String kbId,
            @PathVariable String docId) {
        documentCatalogService.deleteDocument(tenantId, kbId, docId);
        return R.ok(null);
    }

    @GetMapping("/documents/{docId}/versions/{version}/content")
    public R<DocumentContentView> getVersionContent(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @PathVariable String kbId,
            @PathVariable String docId,
            @PathVariable String version) {
        return R.ok(documentCatalogService.getVersionContent(tenantId, kbId, docId, version));
    }

    @PutMapping("/documents/{docId}/versions/{version}/content")
    public R<DocumentContentView> saveDraftContent(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @PathVariable String kbId,
            @PathVariable String docId,
            @PathVariable String version,
            @RequestBody SaveDocumentContentRequest request) {
        return R.ok(documentCatalogService.saveDraftContent(tenantId, kbId, docId, version, request));
    }

    @PostMapping(value = "/documents/{docId}/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<DocumentContentView> uploadMarkdown(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @PathVariable String kbId,
            @PathVariable String docId,
            @RequestParam("file") MultipartFile file) {
        return R.ok(documentCatalogService.uploadMarkdown(tenantId, kbId, docId, file));
    }

    @PostMapping("/documents/{docId}/publish")
    public Mono<R<IngestResult>> publishVersion(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @PathVariable String kbId,
            @PathVariable String docId,
            @RequestParam String version) {
        return documentCatalogService.publishVersion(tenantId, kbId, docId, version).map(R::ok);
    }

    @PostMapping("/documents/{docId}/versions/{version}/fork")
    public R<DocumentDetail> forkVersion(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @PathVariable String kbId,
            @PathVariable String docId,
            @PathVariable String version) {
        return R.ok(documentCatalogService.forkVersion(tenantId, kbId, docId, version));
    }

    @GetMapping("/documents/{docId}/chunks")
    public R<List<ChunkPreviewDto>> listChunks(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @PathVariable String kbId,
            @PathVariable String docId,
            @RequestParam(required = false) String version,
            @RequestParam(defaultValue = "milvus") String store) {
        return R.ok(documentCatalogService.listChunks(tenantId, kbId, docId, version, store));
    }

    @PostMapping("/ingest/text")
    public Mono<R<IngestResult>> ingestText(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @PathVariable String kbId,
            @RequestBody IngestTextRequest request) {
        return documentCatalogService.ingestText(tenantId, kbId, request).map(R::ok);
    }

    @DeleteMapping("/documents/{docId}/versions/{version}")
    public R<Void> supersedeVersion(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @PathVariable String kbId,
            @PathVariable String docId,
            @PathVariable String version) {
        documentCatalogService.supersedeVersion(tenantId, kbId, docId, version);
        return R.ok(null);
    }
}
