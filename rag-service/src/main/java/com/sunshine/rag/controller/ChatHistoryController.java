package com.sunshine.rag.controller;

import com.sunshine.common.core.result.R;
import com.sunshine.rag.service.ChatHistoryMilvusService;
import com.sunshine.rag.service.ChatHistoryRetrievalService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对话历史 RAG — collection {@code sunshine_chat_history}。
 */
@RestController
@RequestMapping("/api/rag/chat-history")
@RequiredArgsConstructor
public class ChatHistoryController {

    private final ChatHistoryRetrievalService chatHistoryRetrievalService;

    @PostMapping("/search")
    public Mono<R<Map<String, Object>>> search(@RequestBody Map<String, Object> body) {
        String userId = str(body, "userId");
        String tenantId = body.containsKey("tenantId") ? str(body, "tenantId") : "default";
        String query = str(body, "query");
        int topK = body.containsKey("topK") ? ((Number) body.get("topK")).intValue() : 5;

        return chatHistoryRetrievalService.search(userId, tenantId, query, topK)
                .map(hits -> R.ok(Map.of(
                        "results", (Object) hits.stream().map(ChatHistoryController::toMap).toList())));
    }

    @PostMapping("/upsert")
    public Mono<R<Map<String, Object>>> upsert(@RequestBody Map<String, Object> body) {
        String userId = str(body, "userId");
        String tenantId = body.containsKey("tenantId") ? str(body, "tenantId") : "default";
        String convId = str(body, "convId");
        String msgId = str(body, "msgId");
        String content = str(body, "content");
        long createdAt = body.containsKey("createdAt") && body.get("createdAt") instanceof Number n
                ? n.longValue()
                : System.currentTimeMillis();

        return chatHistoryRetrievalService.upsert(userId, tenantId, convId, msgId, content, createdAt)
                .thenReturn(R.ok(Map.of("msgId", msgId != null ? msgId : "")));
    }

    @PostMapping("/delete")
    public Mono<R<Map<String, Object>>> delete(@RequestBody Map<String, Object> body) {
        String userId = str(body, "userId");
        String tenantId = body.containsKey("tenantId") ? str(body, "tenantId") : "default";
        String msgId = str(body, "msgId");

        return chatHistoryRetrievalService.delete(userId, tenantId, msgId)
                .thenReturn(R.ok(Map.of("msgId", msgId != null ? msgId : "")));
    }

    private static Map<String, Object> toMap(ChatHistoryMilvusService.ChatHistoryHit hit) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("convId", hit.convId());
        item.put("msgId", hit.msgId());
        item.put("content", hit.content());
        item.put("score", hit.score());
        item.put("createdAt", hit.createdAtMs());
        return item;
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v != null ? v.toString() : null;
    }
}
