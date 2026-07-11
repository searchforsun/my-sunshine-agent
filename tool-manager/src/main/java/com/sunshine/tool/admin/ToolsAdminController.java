package com.sunshine.tool.admin;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.core.result.R;
import com.sunshine.tool.admin.dto.McpServerPatchRequest;
import com.sunshine.tool.admin.dto.ToolPatchRequest;
import com.sunshine.tool.admin.dto.ToolSetMemberAddRequest;
import com.sunshine.tool.admin.dto.ToolSetMemberAddResult;
import com.sunshine.tool.admin.dto.ToolSetMemberCriticalPatchRequest;
import com.sunshine.tool.admin.dto.ToolSetMemberRemoveRequest;
import com.sunshine.tool.admin.dto.ToolSetMembersPageResponse;
import com.sunshine.tool.admin.dto.ToolSetPickerResponse;
import com.sunshine.tool.admin.dto.ToolSetResponse;
import com.sunshine.tool.admin.dto.ToolSetUpdateRequest;
import com.sunshine.tool.entity.McpServerEntity;
import com.sunshine.tool.entity.SdkApplicationEntity;
import com.sunshine.tool.entity.ToolDefinitionEntity;
import com.sunshine.tool.event.ToolCatalogChangePublisher;
import com.sunshine.tool.exception.ToolErrorCode;
import com.sunshine.tool.mcp.McpSyncService;
import com.sunshine.tool.repo.SdkApplicationRepository;
import com.sunshine.tool.repo.ToolDefinitionRepository;
import com.sunshine.tool.sdk.SdkDiscoveryPuller;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class ToolsAdminController {

    private final SdkApplicationRepository sdkApplicationRepository;
    private final SdkDiscoveryPuller sdkDiscoveryPuller;
    private final McpServerAdminService mcpServerAdminService;
    private final McpSyncService mcpSyncService;
    private final ToolDefinitionRepository toolDefinitionRepository;
    private final ToolSetAdminService toolSetAdminService;
    private final ToolSetMemberService toolSetMemberService;
    @Autowired(required = false)
    private ToolCatalogChangePublisher catalogChangePublisher;

    @GetMapping("/tools/sdk-applications")
    public R<List<SdkApplicationEntity>> listSdkApplications() {
        return R.ok(sdkApplicationRepository.findAll());
    }

    @PostMapping("/tools/sdk-applications/{id}/sync")
    public R<Void> syncSdkApplication(@PathVariable String id) {
        sdkDiscoveryPuller.syncOne(id);
        publish("default");
        return R.ok();
    }

    @GetMapping("/mcp/servers")
    public R<List<McpServerEntity>> listMcpServers() {
        return R.ok(mcpServerAdminService.listAll());
    }

    @PostMapping("/mcp/servers")
    public R<McpServerEntity> createMcpServer(@RequestBody McpServerEntity request) {
        return R.ok(mcpServerAdminService.create(request));
    }

    @PostMapping(value = "/mcp/servers/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    public R<List<McpServerEntity>> importMcpServers(@RequestBody String rawJson) {
        return R.ok(mcpServerAdminService.importJson(rawJson));
    }

    @GetMapping(value = "/mcp/servers/export", produces = MediaType.APPLICATION_JSON_VALUE)
    public String exportMcpServers() {
        return mcpServerAdminService.exportJson();
    }

    @PostMapping("/mcp/servers/{id}/probe")
    public R<Void> probeMcpServer(@PathVariable String id) {
        mcpSyncService.probe(id);
        return R.ok();
    }

    @PatchMapping("/mcp/servers/{id}")
    public R<McpServerEntity> patchMcpServer(
            @PathVariable String id,
            @RequestBody McpServerPatchRequest request) {
        return R.ok(mcpServerAdminService.update(id, request));
    }

    @DeleteMapping("/mcp/servers/{id}")
    public R<Void> deleteMcpServer(@PathVariable String id) {
        mcpServerAdminService.delete(id);
        publish("default");
        return R.ok();
    }

    @PatchMapping("/tools/{toolId}")
    public R<ToolDefinitionEntity> patchTool(@PathVariable String toolId, @RequestBody ToolPatchRequest request) {
        ToolDefinitionEntity tool = toolDefinitionRepository.findById(toolId)
                .orElseThrow(() -> new BizException(ToolErrorCode.UNKNOWN_TOOL));
        if (request.enabled() != null) {
            tool.setEnabled(request.enabled());
        }
        if (StringUtils.hasText(request.displayName())) {
            tool.setDisplayName(request.displayName());
            tool.setMetadataEdited(true);
        }
        if (request.description() != null) {
            tool.setDescription(request.description());
            tool.setMetadataEdited(true);
        }
        if (request.requireConfirmation() != null) {
            tool.setRequireConfirmation(request.requireConfirmation());
            tool.setConfirmationEdited(true);
        }
        tool.setUpdatedAt(Instant.now());
        ToolDefinitionEntity saved = toolDefinitionRepository.save(tool);
        publish(tool.getTenantId());
        return R.ok(saved);
    }

    @GetMapping("/tools/sets/react-default")
    public R<ToolSetResponse> getReactDefault(@RequestParam(required = false) String tenantId) {
        return R.ok(toolSetAdminService.getReactDefault(tenantId));
    }

    @PutMapping("/tools/sets/react-default")
    public R<ToolSetResponse> putReactDefault(
            @RequestParam(required = false) String tenantId,
            @RequestBody ToolSetUpdateRequest request) {
        return R.ok(toolSetAdminService.putReactDefault(tenantId, request));
    }

    @GetMapping("/tools/sets/plan-workflow")
    public R<ToolSetResponse> getPlanWorkflow(@RequestParam(required = false) String tenantId) {
        return R.ok(toolSetAdminService.getPlanWorkflow(tenantId));
    }

    @PutMapping("/tools/sets/plan-workflow")
    public R<ToolSetResponse> putPlanWorkflow(
            @RequestParam(required = false) String tenantId,
            @RequestBody ToolSetUpdateRequest request) {
        return R.ok(toolSetAdminService.putPlanWorkflow(tenantId, request));
    }

    @GetMapping("/tools/sets/{kind}/members")
    public R<ToolSetMembersPageResponse> pageToolSetMembers(
            @PathVariable String kind,
            @RequestParam(required = false) String tenantId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String q) {
        return R.ok(toolSetMemberService.pageMembers(ToolSetKind.fromPath(kind), tenantId, page, size, q));
    }

    @GetMapping("/tools/sets/{kind}/picker")
    public R<ToolSetPickerResponse> toolSetPicker(
            @PathVariable String kind,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String q) {
        return R.ok(toolSetMemberService.picker(ToolSetKind.fromPath(kind), tenantId, q));
    }

    @PostMapping("/tools/sets/{kind}/members:add")
    public R<ToolSetMemberAddResult> addToolSetMembers(
            @PathVariable String kind,
            @RequestParam(required = false) String tenantId,
            @RequestBody ToolSetMemberAddRequest request) {
        return R.ok(toolSetMemberService.addMembers(ToolSetKind.fromPath(kind), tenantId, request));
    }

    @PostMapping("/tools/sets/{kind}/members:remove")
    public R<Void> removeToolSetMembers(
            @PathVariable String kind,
            @RequestParam(required = false) String tenantId,
            @RequestBody ToolSetMemberRemoveRequest request) {
        toolSetMemberService.removeMembers(ToolSetKind.fromPath(kind), tenantId, request);
        return R.ok();
    }

    @PatchMapping("/tools/sets/plan-workflow/members/{toolId}")
    public R<Void> patchPlanWorkflowMemberCritical(
            @PathVariable String toolId,
            @RequestParam(required = false) String tenantId,
            @RequestBody ToolSetMemberCriticalPatchRequest request) {
        toolSetMemberService.patchCritical(tenantId, toolId, request);
        return R.ok();
    }

    private void publish(String tenantId) {
        if (catalogChangePublisher != null) {
            catalogChangePublisher.publish(tenantId);
        }
    }
}
