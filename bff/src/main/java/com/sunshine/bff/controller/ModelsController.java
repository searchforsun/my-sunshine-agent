package com.sunshine.bff.controller;

import com.sunshine.bff.client.ModelManagerClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/** BFF 透传 resource-manager 模型注册表（不含 catalog/gateway） */
@RestController
@RequestMapping("/api/models")
@RequiredArgsConstructor
public class ModelsController {

    private final ModelManagerClient modelManagerClient;

    // ---- providers ----

    @GetMapping("/providers")
    public Mono<Map<String, Object>> listProviders(@RequestParam(required = false) String tenantId) {
        return modelManagerClient.listProviders(tenantId);
    }

    @PostMapping("/providers")
    public Mono<Map<String, Object>> createProvider(@RequestBody Map<String, Object> body) {
        return modelManagerClient.createProvider(body);
    }

    @PutMapping("/providers/{id}")
    public Mono<Map<String, Object>> updateProvider(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        return modelManagerClient.updateProvider(id, body);
    }

    @DeleteMapping("/providers/{id}")
    public Mono<Map<String, Object>> deleteProvider(@PathVariable Long id) {
        return modelManagerClient.deleteProvider(id);
    }

    // ---- definitions ----

    @GetMapping("/definitions")
    public Mono<Map<String, Object>> listDefinitions(@RequestParam(required = false) String tenantId) {
        return modelManagerClient.listDefinitions(tenantId);
    }

    @PostMapping("/definitions")
    public Mono<Map<String, Object>> createDefinition(@RequestBody Map<String, Object> body) {
        return modelManagerClient.createDefinition(body);
    }

    @PutMapping("/definitions/{id}")
    public Mono<Map<String, Object>> updateDefinition(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        return modelManagerClient.updateDefinition(id, body);
    }

    @PostMapping("/definitions/{id}/toggle")
    public Mono<Map<String, Object>> toggleDefinition(@PathVariable Long id) {
        return modelManagerClient.toggleDefinition(id);
    }

    @DeleteMapping("/definitions/{id}")
    public Mono<Map<String, Object>> deleteDefinition(@PathVariable Long id) {
        return modelManagerClient.deleteDefinition(id);
    }

    // ---- scenes ----

    @GetMapping("/scenes/keys")
    public Mono<Map<String, Object>> listSceneKeys() {
        return modelManagerClient.listSceneKeys();
    }

    @GetMapping("/scenes")
    public Mono<Map<String, Object>> listScenes(@RequestParam(required = false) String tenantId) {
        return modelManagerClient.listScenes(tenantId);
    }

    @PutMapping("/scenes")
    public Mono<Map<String, Object>> upsertScenes(@RequestBody Object body) {
        return modelManagerClient.upsertScenes(body);
    }

    // ---- catalog（公开；故意不暴露 /catalog/gateway） ----

    @GetMapping("/catalog")
    public Mono<Map<String, Object>> publicCatalog(@RequestParam(required = false) String tenantId) {
        return modelManagerClient.publicCatalog(tenantId);
    }

    // ---- route policy（phase5 5.3 model=auto 策略表） ----

    @GetMapping("/routes/keys")
    public Mono<Map<String, Object>> listRouteKeys() {
        return modelManagerClient.listRouteKeys();
    }

    @GetMapping("/routes")
    public Mono<Map<String, Object>> listRoutes(@RequestParam(required = false) String tenantId) {
        return modelManagerClient.listRoutes(tenantId);
    }

    @PutMapping("/routes")
    public Mono<Map<String, Object>> upsertRoute(@RequestBody Map<String, Object> body) {
        return modelManagerClient.upsertRoute(body);
    }

    @DeleteMapping("/routes/{id}")
    public Mono<Map<String, Object>> deleteRoute(@PathVariable Long id) {
        return modelManagerClient.deleteRoute(id);
    }
}
