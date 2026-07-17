package com.sunshine.workflow.controller;

import com.sunshine.common.core.result.R;
import com.sunshine.workflow.dto.WorkflowCatalogEntry;
import com.sunshine.workflow.dto.WorkflowCreateRequest;
import com.sunshine.workflow.dto.WorkflowDraftRequest;
import com.sunshine.workflow.dto.WorkflowEditableResponse;
import com.sunshine.workflow.dto.WorkflowEnableRequest;
import com.sunshine.workflow.dto.WorkflowListItem;
import com.sunshine.workflow.dto.WorkflowPublishedResponse;
import com.sunshine.workflow.dto.WorkflowNodeDefaultsResponse;
import com.sunshine.workflow.dto.WorkflowPlanValidateRequest;
import com.sunshine.workflow.dto.WorkflowPlanValidateResponse;
import com.sunshine.workflow.dto.WorkflowUpdateRequest;
import com.sunshine.workflow.dto.WorkflowVersionItem;
import com.sunshine.workflow.service.WorkflowAdminService;
import com.sunshine.workflow.service.WorkflowNodeDefaultsService;
import com.sunshine.workflow.service.WorkflowPackageService;
import lombok.RequiredArgsConstructor;
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

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/workflows")
@RequiredArgsConstructor
public class WorkflowAdminController {

    private final WorkflowAdminService adminService;
    private final WorkflowPackageService packageService;
    private final WorkflowNodeDefaultsService nodeDefaultsService;

    @GetMapping("/node-defaults")
    public R<WorkflowNodeDefaultsResponse> nodeDefaults() {
        return R.ok(nodeDefaultsService.getNodeDefaults());
    }

    @GetMapping("/catalog")
    public R<List<WorkflowCatalogEntry>> catalog(
            @RequestHeader(value = "x-tenant-id", required = false) String tenantId) {
        return R.ok(adminService.listCatalog(tenantId));
    }

    @GetMapping
    public R<List<WorkflowListItem>> list(
            @RequestHeader(value = "x-tenant-id", required = false) String tenantId) {
        return R.ok(adminService.listWorkflows(tenantId));
    }

    @GetMapping("/{id}/published")
    public R<WorkflowPublishedResponse> published(
            @PathVariable String id,
            @RequestHeader(value = "x-tenant-id", required = false) String tenantId) {
        return R.ok(adminService.getPublished(id, tenantId));
    }

    @GetMapping("/{id}/editable")
    public R<WorkflowEditableResponse> editable(
            @PathVariable String id,
            @RequestHeader(value = "x-tenant-id", required = false) String tenantId) {
        return R.ok(adminService.getEditable(id, tenantId));
    }

    @GetMapping("/{id}/versions")
    public R<List<WorkflowVersionItem>> versions(
            @PathVariable String id,
            @RequestHeader(value = "x-tenant-id", required = false) String tenantId) {
        return R.ok(adminService.listVersions(id, tenantId));
    }

    @GetMapping("/{id}/versions/{version}")
    public R<WorkflowEditableResponse> versionDetail(
            @PathVariable String id,
            @PathVariable int version,
            @RequestHeader(value = "x-tenant-id", required = false) String tenantId) {
        return R.ok(adminService.getVersion(id, tenantId, version));
    }

    @GetMapping("/{id}/versions/{version}/export")
    public R<Map<String, Object>> exportVersion(
            @PathVariable String id,
            @PathVariable int version,
            @RequestHeader(value = "x-tenant-id", required = false) String tenantId) {
        return R.ok(packageService.exportPackage(id, tenantId, version));
    }

    @PostMapping
    public R<WorkflowListItem> create(
            @RequestHeader(value = "x-tenant-id", required = false) String tenantId,
            @RequestBody WorkflowCreateRequest request) {
        return R.ok(adminService.create(tenantId, request));
    }

    @PutMapping("/{id}")
    public R<WorkflowListItem> update(
            @PathVariable String id,
            @RequestHeader(value = "x-tenant-id", required = false) String tenantId,
            @RequestBody WorkflowUpdateRequest request) {
        return R.ok(adminService.updateMeta(id, tenantId, request));
    }

    @PutMapping("/{id}/enable")
    public R<WorkflowListItem> enable(
            @PathVariable String id,
            @RequestHeader(value = "x-tenant-id", required = false) String tenantId,
            @RequestBody WorkflowEnableRequest request) {
        return R.ok(adminService.setEnabled(id, tenantId, request));
    }

    @PostMapping("/plan/validate")
    public R<WorkflowPlanValidateResponse> validatePlan(@RequestBody WorkflowPlanValidateRequest request) {
        return R.ok(adminService.validatePlan(request.plan()));
    }

    @PutMapping("/{id}/draft")
    public R<Void> saveDraft(
            @PathVariable String id,
            @RequestHeader(value = "x-tenant-id", required = false) String tenantId,
            @RequestBody WorkflowDraftRequest request) {
        adminService.saveDraft(id, tenantId, request);
        return R.ok();
    }

    @PostMapping("/{id}/publish")
    public R<WorkflowPublishedResponse> publish(
            @PathVariable String id,
            @RequestHeader(value = "x-tenant-id", required = false) String tenantId,
            @RequestParam(required = false) Integer version) {
        return R.ok(adminService.publish(id, tenantId, version));
    }

    @PostMapping("/{id}/versions/{version}/fork")
    public R<WorkflowListItem> fork(
            @PathVariable String id,
            @PathVariable int version,
            @RequestHeader(value = "x-tenant-id", required = false) String tenantId) {
        return R.ok(packageService.forkVersion(id, tenantId, version));
    }

    @PostMapping("/import")
    public R<WorkflowListItem> importPackage(
            @RequestHeader(value = "x-tenant-id", required = false) String tenantId,
            @RequestBody Map<String, Object> body) {
        return R.ok(packageService.importPackage(tenantId, body));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(
            @PathVariable String id,
            @RequestHeader(value = "x-tenant-id", required = false) String tenantId) {
        adminService.delete(id, tenantId);
        return R.ok();
    }

    @DeleteMapping("/{id}/versions/{version}")
    public R<WorkflowListItem> deleteVersion(
            @PathVariable String id,
            @PathVariable int version,
            @RequestHeader(value = "x-tenant-id", required = false) String tenantId) {
        return R.ok(adminService.deleteVersion(id, tenantId, version));
    }
}
