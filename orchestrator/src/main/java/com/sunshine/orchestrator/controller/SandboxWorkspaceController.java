package com.sunshine.orchestrator.controller;

import com.sunshine.common.sandbox.FsContentDto;
import com.sunshine.common.sandbox.FsNodeDto;
import com.sunshine.orchestrator.config.ReactiveBlocking;
import com.sunshine.orchestrator.sandbox.SandboxWorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class SandboxWorkspaceController {

    private final SandboxWorkspaceService workspaceService;

    @GetMapping("/conversations/{id}/sandbox/workspace")
    public Mono<FsNodeDto.FsListResponse> list(
            @PathVariable("id") String id,
            @RequestParam(value = "path", required = false, defaultValue = "/workspace") String path,
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return ReactiveBlocking.call(() -> workspaceService.list(id, userId, tenantId, path));
    }

    @GetMapping("/conversations/{id}/sandbox/workspace/index")
    public Mono<FsNodeDto.FsIndexResponse> listIndex(
            @PathVariable("id") String id,
            @RequestParam(value = "path", required = false, defaultValue = "/workspace") String path,
            @RequestParam(value = "maxDepth", required = false, defaultValue = "64") int maxDepth,
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return ReactiveBlocking.call(() -> workspaceService.index(id, userId, tenantId, path, maxDepth));
    }

    @GetMapping("/conversations/{id}/sandbox/workspace/content")
    public Mono<FsContentDto> content(
            @PathVariable("id") String id,
            @RequestParam("path") String path,
            @RequestParam(value = "offset", required = false, defaultValue = "0") int offset,
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return ReactiveBlocking.call(() -> workspaceService.content(id, userId, tenantId, path, offset));
    }

    @GetMapping("/conversations/{id}/sandbox/workspace/status")
    public Mono<Map<String, Object>> status(
            @PathVariable("id") String id,
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return ReactiveBlocking.call(() -> Map.of(
                "active", workspaceService.hasActiveWorkspace(id, userId, tenantId)));
    }
}
