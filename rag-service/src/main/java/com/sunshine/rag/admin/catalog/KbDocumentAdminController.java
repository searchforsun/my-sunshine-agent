package com.sunshine.rag.admin.catalog;

import com.sunshine.common.core.result.R;
import com.sunshine.rag.admin.catalog.dto.ChunkPreviewDto;
import com.sunshine.rag.admin.catalog.dto.DocumentDetail;
import com.sunshine.rag.admin.catalog.dto.DocumentSummary;
import com.sunshine.rag.admin.catalog.dto.IngestResult;
import com.sunshine.rag.admin.catalog.dto.IngestTextRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
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

    @GetMapping("/documents/{docId}")
    public R<DocumentDetail> getDocument(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @PathVariable String kbId,
            @PathVariable String docId) {
        return R.ok(documentCatalogService.getDocument(tenantId, kbId, docId));
    }

    @GetMapping("/documents/{docId}/chunks")
    public R<List<ChunkPreviewDto>> listChunks(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @PathVariable String kbId,
            @PathVariable String docId,
            @RequestParam(required = false) Integer version) {
        return R.ok(documentCatalogService.listChunks(tenantId, kbId, docId, version));
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
            @PathVariable int version) {
        documentCatalogService.supersedeVersion(tenantId, kbId, docId, version);
        return R.ok(null);
    }
}
