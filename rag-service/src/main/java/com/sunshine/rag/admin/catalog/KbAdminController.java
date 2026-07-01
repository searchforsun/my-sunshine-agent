package com.sunshine.rag.admin.catalog;

import com.sunshine.common.core.result.R;
import com.sunshine.rag.admin.catalog.dto.CreateKbRequest;
import com.sunshine.rag.admin.catalog.dto.KbSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/rag/admin/kbs")
@RequiredArgsConstructor
public class KbAdminController {

    private final KnowledgeBaseService knowledgeBaseService;

    @GetMapping
    public R<List<KbSummary>> list(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return R.ok(knowledgeBaseService.listByTenant(tenantId));
    }

    @PostMapping
    public R<KbSummary> create(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @RequestBody CreateKbRequest request) {
        return R.ok(knowledgeBaseService.create(tenantId, request));
    }

    @PutMapping("/{kbId}/default")
    public R<KbSummary> setDefault(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @PathVariable String kbId) {
        return R.ok(knowledgeBaseService.setDefault(tenantId, kbId));
    }
}
