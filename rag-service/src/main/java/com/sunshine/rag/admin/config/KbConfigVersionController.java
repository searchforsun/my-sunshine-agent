package com.sunshine.rag.admin.config;

import com.sunshine.common.core.result.R;
import com.sunshine.rag.admin.config.dto.ConfigBundleDraftView;
import com.sunshine.rag.admin.eval.dto.ApplySuggestionsRequest;
import com.sunshine.rag.admin.eval.dto.ConfigSuggestionItem;
import com.sunshine.rag.admin.config.dto.ConfigSchemaResponse;
import com.sunshine.rag.admin.config.dto.ConfigVersionSummary;
import com.sunshine.rag.admin.config.dto.PublishBundleResult;
import com.sunshine.rag.admin.config.dto.SubmitEvalResult;
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

@RestController
@RequestMapping("/api/rag/admin/kbs/{kbId}/config")
@RequiredArgsConstructor
public class KbConfigVersionController {

    private final ConfigVersionService configVersionService;
    private final RagConfigSchemaService schemaService;

    @GetMapping("/schema")
    public R<ConfigSchemaResponse> schema(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @PathVariable String kbId) {
        configVersionService.requireBundle(tenantId, kbId);
        return R.ok(schemaService.getSchema(tenantId, kbId));
    }

    @GetMapping(value = "/effective", params = "mode")
    public R<Map<String, Object>> effective(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @PathVariable String kbId,
            @RequestParam String mode,
            @RequestParam(required = false) Long versionId) {
        return R.ok(configVersionService.getEffective(tenantId, kbId, mode, versionId));
    }

    @GetMapping("/draft")
    public R<ConfigBundleDraftView> draft(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @PathVariable String kbId) {
        return R.ok(configVersionService.getDraftView(tenantId, kbId));
    }

    @PutMapping("/draft")
    public R<Map<String, Object>> saveDraft(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @RequestHeader(value = "x-user-id", required = false) String userId,
            @PathVariable String kbId,
            @RequestBody Map<String, Object> payload) {
        return R.ok(configVersionService.saveDraft(tenantId, kbId, payload, userId));
    }

    @PostMapping("/draft/apply-suggestions")
    public R<Map<String, Object>> applySuggestions(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @PathVariable String kbId,
            @RequestBody ApplySuggestionsRequest request) {
        return R.ok(configVersionService.applySuggestions(
                tenantId, kbId,
                request != null ? request.suggestions() : List.of(),
                request != null ? request.versionId() : null));
    }

    @PostMapping("/versions/{versionId}/fork")
    public R<Map<String, Object>> forkToDraft(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @PathVariable String kbId,
            @PathVariable Long versionId) {
        return R.ok(configVersionService.forkToDraft(tenantId, kbId, versionId));
    }

    @PostMapping("/publish")
    public R<SubmitEvalResult> publish(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @PathVariable String kbId) {
        return R.ok(configVersionService.submitEval(tenantId, kbId));
    }

    @PostMapping("/versions/{versionId}/revert-to-draft")
    public R<Map<String, Object>> revertToDraft(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @PathVariable String kbId,
            @PathVariable Long versionId) {
        return R.ok(configVersionService.revertToDraft(tenantId, kbId, versionId));
    }

    @GetMapping("/versions")
    public R<List<ConfigVersionSummary>> versions(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @PathVariable String kbId) {
        return R.ok(configVersionService.listVersions(tenantId, kbId));
    }

    @PostMapping("/versions/{versionId}/activate")
    public R<PublishBundleResult> activate(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @PathVariable String kbId,
            @PathVariable Long versionId) {
        return R.ok(configVersionService.activate(tenantId, kbId, versionId));
    }
}
