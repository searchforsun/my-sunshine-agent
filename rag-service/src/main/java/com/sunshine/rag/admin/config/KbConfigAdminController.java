package com.sunshine.rag.admin.config;

import com.sunshine.common.core.result.R;
import com.sunshine.rag.admin.config.dto.ConfigDraftSummary;
import com.sunshine.rag.admin.config.dto.ConfigSchemaResponse;
import com.sunshine.rag.admin.config.dto.PublishDraftResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * @deprecated 过渡期 per-scope 端点，T25 后由 {@link KbConfigVersionController} 取代。
 */
@Deprecated
@RestController
@RequestMapping("/api/rag/admin/config")
@RequiredArgsConstructor
public class KbConfigAdminController {

    private final RagConfigSchemaService schemaService;
    private final ConfigDraftService draftService;
    private final ConfigPublishService publishService;
    private final EffectiveConfigService effectiveConfigService;

    /** @deprecated 使用 GET /api/rag/admin/kbs/{kbId}/config/schema */
    @Deprecated
    @GetMapping("/schema")
    public R<ConfigSchemaResponse> schema(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @RequestParam(required = false) String kbId) {
        return R.ok(schemaService.getSchema(tenantId, kbId));
    }

    @GetMapping("/drafts")
    public R<List<ConfigDraftSummary>> drafts(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return R.ok(draftService.listDrafts(tenantId));
    }

    @GetMapping("/drafts/{scope}")
    public R<ConfigDraftSummary> draft(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @PathVariable String scope) {
        return draftService.getDraft(tenantId, scope)
                .map(R::ok)
                .orElseGet(() -> R.ok(null));
    }

    /** @deprecated 使用 PUT /api/rag/admin/kbs/{kbId}/config/draft */
    @Deprecated
    @PutMapping("/drafts/{scope}")
    public R<ConfigDraftSummary> saveDraft(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @RequestHeader(value = "x-user-id", required = false) String userId,
            @PathVariable String scope,
            @RequestBody Map<String, Object> payload) {
        return R.ok(draftService.saveDraft(tenantId, scope, payload, userId));
    }

    /** @deprecated 使用 POST /api/rag/admin/kbs/{kbId}/config/publish */
    @Deprecated
    @PostMapping("/drafts/{scope}/publish")
    public R<PublishDraftResult> publishDraft(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @PathVariable String scope,
            @RequestParam(defaultValue = "default") String kbId) {
        return R.ok(publishService.publishDraft(tenantId, scope, kbId));
    }

    @GetMapping("/effective")
    public R<EffectiveRagConfig> effective(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @RequestParam(defaultValue = "default") String kbId) {
        return R.ok(effectiveConfigService.resolve(tenantId, kbId));
    }
}
