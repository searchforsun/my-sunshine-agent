package com.sunshine.bff.controller;

import com.sunshine.bff.client.ExpertManagerClient;
import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.core.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/** BFF 透传 expert-manager Catalog / Admin */
@RestController
@RequiredArgsConstructor
public class ExpertsController {

    private final ExpertManagerClient expertManagerClient;

    @GetMapping("/api/experts")
    public Mono<Map<String, Object>> listExperts() {
        return expertManagerClient.listExperts();
    }

    @PostMapping("/api/experts")
    public Mono<Map<String, Object>> createExpert(@RequestBody Map<String, Object> body) {
        return expertManagerClient.createExpert(body);
    }

    @PutMapping("/api/experts/{id}")
    public Mono<Map<String, Object>> updateExpert(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        return expertManagerClient.updateExpert(id, body);
    }

    @PutMapping("/api/experts/{id}/enable")
    public Mono<Map<String, Object>> enableExpert(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        return expertManagerClient.setEnabled(id, enabled);
    }

    @DeleteMapping("/api/experts/{id}")
    public Mono<Map<String, Object>> deleteExpert(@PathVariable String id) {
        return expertManagerClient.deleteExpert(id);
    }

    @GetMapping("/api/experts/catalog/index")
    public Mono<Map<String, Object>> expertCatalogIndex() {
        return expertManagerClient.catalogIndex();
    }

    @GetMapping("/api/experts/catalog")
    public Mono<Map<String, Object>> expertCatalogRemoved() {
        return Mono.error(new BizException(CommonErrorCode.GONE));
    }

    @GetMapping("/api/experts/{id}/catalog")
    public Mono<Map<String, Object>> expertCatalogDetail(@PathVariable String id) {
        return expertManagerClient.catalogDetail(id);
    }
}
