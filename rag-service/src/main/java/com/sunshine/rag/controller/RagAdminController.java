package com.sunshine.rag.controller;

import com.sunshine.rag.config.RagAdminProperties;
import com.sunshine.rag.service.MilvusService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/** RAG 运维 Admin API */
@RestController
@RequestMapping("/api/rag/admin")
@RequiredArgsConstructor
public class RagAdminController {

    private final MilvusService milvusService;
    private final RagAdminProperties adminProperties;

    @PostMapping("/rebuild")
    public Mono<Map<String, Object>> rebuild(
            @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        String required = adminProperties.getToken();
        if (required != null && !required.isBlank() && !required.equals(token)) {
            return Mono.just(Map.of("code", 403, "msg", "无效 admin token"));
        }
        milvusService.rebuildCollection();
        return Mono.just(Map.of(
                "code", 200,
                "msg", "rebuild ok",
                "collection", "sunshine_knowledge"));
    }
}
