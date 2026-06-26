package com.sunshine.rag.controller;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.core.result.R;
import com.sunshine.rag.config.RagAdminProperties;
import com.sunshine.rag.exception.RagErrorCode;
import com.sunshine.rag.service.ElasticsearchIndexService;
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
    private final ElasticsearchIndexService elasticsearchIndexService;
    private final RagAdminProperties adminProperties;

    @PostMapping("/rebuild")
    public Mono<R<Map<String, Object>>> rebuild(
            @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        String required = adminProperties.getToken();
        if (required != null && !required.isBlank() && !required.equals(token)) {
            return Mono.error(new BizException(RagErrorCode.ADMIN_TOKEN_INVALID));
        }
        milvusService.rebuildCollection();
        elasticsearchIndexService.rebuildIndex();
        return Mono.just(R.ok(Map.of(
                "collection", "sunshine_knowledge",
                "msg", "rebuild ok"
        )));
    }
}
