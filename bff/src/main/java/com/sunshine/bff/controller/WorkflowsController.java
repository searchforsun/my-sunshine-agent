package com.sunshine.bff.controller;

import com.sunshine.bff.client.WorkflowManagerClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/** BFF 透传 workflow-manager */
@RestController
@RequiredArgsConstructor
public class WorkflowsController {

    private final WorkflowManagerClient workflowManagerClient;

    @GetMapping("/api/workflows/catalog")
    public Mono<Map<String, Object>> catalog() {
        return workflowManagerClient.catalog();
    }

    @GetMapping("/api/workflows/node-defaults")
    public Mono<Map<String, Object>> nodeDefaults() {
        return workflowManagerClient.nodeDefaults();
    }

    @GetMapping("/api/workflows")
    public Mono<Map<String, Object>> list() {
        return workflowManagerClient.list();
    }

    @GetMapping("/api/workflows/{id}/published")
    public Mono<Map<String, Object>> published(@PathVariable String id) {
        return workflowManagerClient.published(id);
    }

    @GetMapping("/api/workflows/{id}/editable")
    public Mono<Map<String, Object>> editable(@PathVariable String id) {
        return workflowManagerClient.editable(id);
    }

    @GetMapping("/api/workflows/{id}/versions")
    public Mono<Map<String, Object>> versions(@PathVariable String id) {
        return workflowManagerClient.versions(id);
    }

    @GetMapping("/api/workflows/{id}/versions/{version}")
    public Mono<Map<String, Object>> versionDetail(
            @PathVariable String id,
            @PathVariable int version) {
        return workflowManagerClient.versionDetail(id, version);
    }

    @GetMapping("/api/workflows/{id}/versions/{version}/export")
    public Mono<Map<String, Object>> exportVersion(
            @PathVariable String id,
            @PathVariable int version) {
        return workflowManagerClient.exportVersion(id, version);
    }

    @PostMapping("/api/workflows")
    public Mono<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        return workflowManagerClient.create(body);
    }

    @PutMapping("/api/workflows/{id}")
    public Mono<Map<String, Object>> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return workflowManagerClient.update(id, body);
    }

    @PutMapping("/api/workflows/{id}/enable")
    public Mono<Map<String, Object>> enable(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return workflowManagerClient.enable(id, body);
    }

    @PutMapping("/api/workflows/{id}/draft")
    public Mono<Map<String, Object>> saveDraft(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return workflowManagerClient.saveDraft(id, body);
    }

    @PostMapping("/api/workflows/plan/validate")
    public Mono<Map<String, Object>> validatePlan(@RequestBody Map<String, Object> body) {
        return workflowManagerClient.validatePlan(body);
    }

    @PostMapping("/api/workflows/{id}/publish")
    public Mono<Map<String, Object>> publish(
            @PathVariable String id,
            @RequestParam(required = false) Integer version) {
        return workflowManagerClient.publish(id, version);
    }

    @PostMapping("/api/workflows/{id}/versions/{version}/fork")
    public Mono<Map<String, Object>> fork(@PathVariable String id, @PathVariable int version) {
        return workflowManagerClient.fork(id, version);
    }

    @PostMapping("/api/workflows/import")
    public Mono<Map<String, Object>> importPackage(@RequestBody Map<String, Object> body) {
        return workflowManagerClient.importPackage(body);
    }

    @DeleteMapping("/api/workflows/{id}")
    public Mono<Map<String, Object>> delete(@PathVariable String id) {
        return workflowManagerClient.delete(id);
    }

    @DeleteMapping("/api/workflows/{id}/versions/{version}")
    public Mono<Map<String, Object>> deleteVersion(@PathVariable String id, @PathVariable int version) {
        return workflowManagerClient.deleteVersion(id, version);
    }
}
