package com.sunshine.bff.controller;

import com.sunshine.bff.client.ToolManagerAdminClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/** BFF 透传 tool-manager Admin API */
@RestController
@RequiredArgsConstructor
public class ToolsAdminController {

    private final ToolManagerAdminClient toolManagerAdminClient;

    @GetMapping("/api/tools/catalog")
    public Mono<Map<String, Object>> toolCatalog(
            @RequestParam(required = false) String tenantId,
            @RequestParam(defaultValue = "false") boolean enabledOnly) {
        return toolManagerAdminClient.catalog(tenantId, enabledOnly);
    }

    @GetMapping("/api/admin/tools/sdk-applications")
    public Mono<Map<String, Object>> listSdkApplications() {
        return toolManagerAdminClient.listSdkApplications();
    }

    @PostMapping("/api/admin/tools/sdk-applications/{id}/sync")
    public Mono<Map<String, Object>> syncSdkApplication(@PathVariable String id) {
        return toolManagerAdminClient.syncSdkApplication(id);
    }

    @GetMapping("/api/admin/mcp/servers")
    public Mono<Map<String, Object>> listMcpServers() {
        return toolManagerAdminClient.listMcpServers();
    }

    @PostMapping("/api/admin/mcp/servers")
    public Mono<Map<String, Object>> createMcpServer(@RequestBody Map<String, Object> body) {
        return toolManagerAdminClient.createMcpServer(body);
    }

    @PostMapping(value = "/api/admin/mcp/servers/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> importMcpServers(@RequestBody String rawJson) {
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
    public Mono<Map<String, Object>> probeMcpServer(@PathVariable String id) {
        return toolManagerAdminClient.probeMcpServer(id);
    }

    @PatchMapping("/api/admin/mcp/servers/{id}")
    public Mono<Map<String, Object>> patchMcpServer(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        return toolManagerAdminClient.patchMcpServer(id, body);
    }

    @DeleteMapping("/api/admin/mcp/servers/{id}")
    public Mono<Map<String, Object>> deleteMcpServer(@PathVariable String id) {
        return toolManagerAdminClient.deleteMcpServer(id);
    }

    @PatchMapping("/api/admin/tools/{toolId}")
    public Mono<Map<String, Object>> patchTool(
            @PathVariable String toolId,
            @RequestBody Map<String, Object> body) {
        return toolManagerAdminClient.patchTool(toolId, body);
    }

    @GetMapping("/api/admin/tools/sets/react-default")
    public Mono<Map<String, Object>> getReactDefaultToolSet(
            @RequestParam(required = false) String tenantId) {
        return toolManagerAdminClient.getReactDefaultToolSet(tenantId);
    }

    @PutMapping("/api/admin/tools/sets/react-default")
    public Mono<Map<String, Object>> putReactDefaultToolSet(
            @RequestParam(required = false) String tenantId,
            @RequestBody Map<String, Object> body) {
        return toolManagerAdminClient.putReactDefaultToolSet(tenantId, body);
    }

    @GetMapping("/api/admin/tools/sets/plan-workflow")
    public Mono<Map<String, Object>> getPlanWorkflowToolSet(
            @RequestParam(required = false) String tenantId) {
        return toolManagerAdminClient.getPlanWorkflowToolSet(tenantId);
    }

    @PutMapping("/api/admin/tools/sets/plan-workflow")
    public Mono<Map<String, Object>> putPlanWorkflowToolSet(
            @RequestParam(required = false) String tenantId,
            @RequestBody Map<String, Object> body) {
        return toolManagerAdminClient.putPlanWorkflowToolSet(tenantId, body);
    }

    @GetMapping("/api/admin/tools/sets/{kind}/members")
    public Mono<Map<String, Object>> pageToolSetMembers(
            @PathVariable String kind,
            @RequestParam(required = false) String tenantId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String q) {
        return toolManagerAdminClient.pageToolSetMembers(kind, tenantId, page, size, q);
    }

    @GetMapping("/api/admin/tools/sets/{kind}/picker")
    public Mono<Map<String, Object>> toolSetPicker(
            @PathVariable String kind,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String q) {
        return toolManagerAdminClient.toolSetPicker(kind, tenantId, q);
    }

    @PostMapping("/api/admin/tools/sets/{kind}/members:add")
    public Mono<Map<String, Object>> addToolSetMembers(
            @PathVariable String kind,
            @RequestParam(required = false) String tenantId,
            @RequestBody Map<String, Object> body) {
        return toolManagerAdminClient.addToolSetMembers(kind, tenantId, body);
    }

    @PostMapping("/api/admin/tools/sets/{kind}/members:remove")
    public Mono<Map<String, Object>> removeToolSetMembers(
            @PathVariable String kind,
            @RequestParam(required = false) String tenantId,
            @RequestBody Map<String, Object> body) {
        return toolManagerAdminClient.removeToolSetMembers(kind, tenantId, body);
    }

    @PatchMapping("/api/admin/tools/sets/plan-workflow/members/{toolId}")
    public Mono<Map<String, Object>> patchPlanWorkflowMemberCritical(
            @PathVariable String toolId,
            @RequestParam(required = false) String tenantId,
            @RequestBody Map<String, Object> body) {
        return toolManagerAdminClient.patchPlanWorkflowMemberCritical(tenantId, toolId, body);
    }
}
