package com.sunshine.rag.controller;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.core.result.R;
import com.sunshine.rag.admin.catalog.DocumentCatalogService;
import com.sunshine.rag.exception.RagErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 文档入库控制器 — 委托 catalog；保留旧路径兼容。
 */
@Slf4j
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class IngestionController {

    private final DocumentCatalogService documentCatalogService;

    @PostMapping("/documents")
    public Mono<R<Map<String, Object>>> ingest(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        if (body == null || body.get("content") == null || body.get("content").isBlank()) {
            throw new BizException(RagErrorCode.CONTENT_EMPTY);
        }
        return documentCatalogService.ingestLegacy(tenantId, body).map(R::ok);
    }
}
