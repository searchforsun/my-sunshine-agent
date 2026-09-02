package com.sunshine.bff.controller;

import com.sunshine.bff.client.PromptManagerClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/** BFF 透传 prompt-manager Admin / Catalog / Routing */
@RestController
@RequiredArgsConstructor
public class PromptsController {

    private final PromptManagerClient promptManagerClient;

    @GetMapping("/api/prompts")
    public Mono<Map<String, Object>> listPrompts(
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) Boolean enabled) {
        return promptManagerClient.listPrompts(kind, enabled);
    }

    @GetMapping("/api/prompts/catalog")
    public Mono<Map<String, Object>> promptCatalog() {
        return promptManagerClient.catalog();
    }

    @PostMapping("/api/prompts/routing/validate")
    public Mono<Map<String, Object>> routingValidate(@RequestBody(required = false) Map<String, Object> body) {
        return promptManagerClient.routingValidate(body);
    }

    @PostMapping("/api/prompts/routing/dry-run")
    public Mono<Map<String, Object>> routingDryRun(@RequestBody Map<String, Object> body) {
        return promptManagerClient.routingDryRun(body);
    }

    @GetMapping("/api/prompts/{id}")
    public Mono<Map<String, Object>> getPrompt(@PathVariable String id) {
        return promptManagerClient.getPrompt(id);
    }

    @PostMapping("/api/prompts")
    public Mono<Map<String, Object>> createPrompt(@RequestBody Map<String, Object> body) {
        return promptManagerClient.createPrompt(body);
    }

    @PutMapping("/api/prompts/{id}")
    public Mono<Map<String, Object>> updatePrompt(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        return promptManagerClient.updatePrompt(id, body);
    }

    @PutMapping("/api/prompts/{id}/enable")
    public Mono<Map<String, Object>> enablePrompt(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        return promptManagerClient.setEnabled(id, body);
    }

    @GetMapping("/api/prompts/{id}/versions")
    public Mono<Map<String, Object>> listVersions(@PathVariable String id) {
        return promptManagerClient.listVersions(id);
    }

    @PostMapping("/api/prompts/{id}/versions")
    public Mono<Map<String, Object>> addVersion(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        return promptManagerClient.addVersion(id, body);
    }

    @PostMapping("/api/prompts/{id}/publish")
    public Mono<Map<String, Object>> publish(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        return promptManagerClient.publish(id, body);
    }

    @PostMapping("/api/prompts/{id}/rollback")
    public Mono<Map<String, Object>> rollback(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        return promptManagerClient.rollback(id, body);
    }
}
