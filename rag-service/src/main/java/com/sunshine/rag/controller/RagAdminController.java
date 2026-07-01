package com.sunshine.rag.controller;

import com.sunshine.common.core.result.R;
import com.sunshine.rag.service.ElasticsearchIndexService;
import com.sunshine.rag.service.MilvusService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/** RAG 运维 Admin API — 鉴权见 {@link com.sunshine.rag.admin.AdminTokenFilter} */
@RestController
@RequestMapping("/api/rag/admin")
@RequiredArgsConstructor
public class RagAdminController {

    private final MilvusService milvusService;
    private final ElasticsearchIndexService elasticsearchIndexService;

    @PostMapping("/rebuild")
    public Mono<R<Map<String, Object>>> rebuild() {
        milvusService.rebuildCollection();
        elasticsearchIndexService.rebuildIndex();
        return Mono.just(R.ok(Map.of(
                "collection", "sunshine_knowledge",
                "msg", "rebuild ok"
        )));
    }
}
