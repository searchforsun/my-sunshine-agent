package com.sunshine.bff.controller;

import com.sunshine.common.core.result.R;
import com.sunshine.common.tool.ToolCatalogEntry;
import com.sunshine.common.tool.admin.McpServerPatchRequest;
import com.sunshine.common.tool.admin.McpServerView;
import com.sunshine.common.tool.admin.SdkApplicationView;
import com.sunshine.common.tool.admin.ToolDefinitionView;
import com.sunshine.common.tool.admin.ToolPatchRequest;
import com.sunshine.common.tool.admin.ToolSetMemberAddRequest;
import com.sunshine.common.tool.admin.ToolSetMemberAddResult;
import com.sunshine.common.tool.admin.ToolSetMemberRemoveRequest;
import com.sunshine.common.tool.admin.ToolSetMembersPageResponse;
import com.sunshine.common.tool.admin.ToolSetPickerResponse;
import com.sunshine.common.tool.admin.ToolSetToolIdsResponse;
import com.sunshine.bff.client.ToolManagerAdminClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/** BFF 透传 tool-manager Admin API */
@RestController
@RequiredArgsConstructor
public class ToolsAdminController {

    private final ToolManagerAdminClient toolManagerAdminClient;

    @GetMapping("/api/tools/catalog")
    public Mono<R<List<ToolCatalogEntry>>> toolCatalog(
            @RequestParam(required = false) String tenantId,
            @RequestParam(defaultValue = "false") boolean enabledOnly) {
        return toolManagerAdminClient.catalog(tenantId, enabledOnly);
    }

    @GetMapping("/api/admin/tools/sdk-applications")
    public Mono<R<List<SdkApplicationView>>> listSdkApplications() {
        return toolManagerAdminClient.listSdkApplications();
    }

    @PostMapping("/api/admin/tools/sdk-applications/{id}/sync")
    public Mono<R<Void>> syncSdkApplication(@PathVariable String id) {
        return toolManagerAdminClient.syncSdkApplication(id);
    }

    @GetMapping("/api/admin/mcp/servers")
    public Mono<R<List<McpServerView>>> listMcpServers() {
        return toolManagerAdminClient.listMcpServers();
    }

    @PostMapping("/api/admin/mcp/servers")
    public Mono<R<McpServerView>> createMcpServer(@RequestBody McpServerView body) {
        return toolManagerAdminClient.createMcpServer(body);
    }

    @PostMapping(value = "/api/admin/mcp/servers/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<R<List<McpServerView>>> importMcpServers(@RequestBody String rawJson) {
        return toolManagerAdminClient.importMcpServers(rawJson);
    }

    @GetMapping(value = "/api/admin/mcp/servers/export", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<String>> exportMcpServers() {
        return toolManagerAdminClient.exportMcpServers()
                .map(json -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(json));
    }

    @PostMapping("/api/admin/mcp/servers/{id}/probe")
    public Mono<R<Void>> probeMcpServer(@PathVariable String id) {
        return toolManagerAdminClient.probeMcpServer(id);
    }

    @PatchMapping("/api/admin/mcp/servers/{id}")
    public Mono<R<McpServerView>> patchMcpServer(
            @PathVariable String id,
            @RequestBody McpServerPatchRequest body) {
        return toolManagerAdminClient.patchMcpServer(id, body);
    }

    @DeleteMapping("/api/admin/mcp/servers/{id}")
    public Mono<R<Void>> deleteMcpServer(@PathVariable String id) {
        return toolManagerAdminClient.deleteMcpServer(id);
    }

    @PatchMapping("/api/admin/tools/{toolId}")
    public Mono<R<ToolDefinitionView>> patchTool(
            @PathVariable String toolId,
            @RequestBody ToolPatchRequest body) {
        return toolManagerAdminClient.patchTool(toolId, body);
    }

    @GetMapping("/api/admin/tools/sets/{kind}/members")
    public Mono<R<ToolSetMembersPageResponse>> pageToolSetMembers(
            @PathVariable String kind,
            @RequestParam(required = false) String tenantId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String q) {
        return toolManagerAdminClient.pageToolSetMembers(kind, tenantId, page, size, q);
    }

    @GetMapping("/api/admin/tools/sets/{kind}/picker")
    public Mono<R<ToolSetPickerResponse>> toolSetPicker(
            @PathVariable String kind,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String q) {
        return toolManagerAdminClient.toolSetPicker(kind, tenantId, q);
    }

    /** A-4：skill/agent 声明 picker 按 (tenant, kind) 集过滤候选；kind=all = chat∪task 并集 */
    @GetMapping("/api/admin/tools/sets/{kind}/tool-ids")
    public Mono<R<ToolSetToolIdsResponse>> toolSetToolIds(
            @PathVariable String kind,
            @RequestParam(required = false) String tenantId) {
        return toolManagerAdminClient.toolSetToolIds(kind, tenantId);
    }

    @PostMapping("/api/admin/tools/sets/{kind}/members:add")
    public Mono<R<ToolSetMemberAddResult>> addToolSetMembers(
            @PathVariable String kind,
            @RequestParam(required = false) String tenantId,
            @RequestBody ToolSetMemberAddRequest body) {
        return toolManagerAdminClient.addToolSetMembers(kind, tenantId, body);
    }

    @PostMapping("/api/admin/tools/sets/{kind}/members:remove")
    public Mono<R<Void>> removeToolSetMembers(
            @PathVariable String kind,
            @RequestParam(required = false) String tenantId,
            @RequestBody ToolSetMemberRemoveRequest body) {
        return toolManagerAdminClient.removeToolSetMembers(kind, tenantId, body);
    }
}
