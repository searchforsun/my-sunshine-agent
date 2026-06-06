package com.sunshine.rag.controller;

import com.sunshine.rag.service.RetrievalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 检索控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RetrievalController {

    private final RetrievalService retrievalService;

    @PostMapping("/search")
    public Mono<Map<String, Object>> search(@RequestBody Map<String, Object> body) {
        String query = (String) body.get("query");
        int topK = body.containsKey("topK")
                ? ((Number) body.get("topK")).intValue()
                : 5;

        return retrievalService.search(query, topK)
                .map(fragments -> Map.of(
                        "code", 200,
                        "query", query,
                        "results", (Object) fragments.stream()
                                .map(RetrievalService.DocFragment::content)
                                .toList()
                ));
    }
}
