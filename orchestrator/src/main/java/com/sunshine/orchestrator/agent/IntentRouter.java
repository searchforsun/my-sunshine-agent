package com.sunshine.orchestrator.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 意图识别 — 轻量分类，决定走直连流式还是 Agent（RAG/工具）
 */
@Slf4j
@Component
public class IntentRouter {

    @Value("${agent.model.base-url:http://127.0.0.1:8300/v1}")
    private String baseUrl;

    @Value("${agent.model.api-key:}")
    private String apiKey;

    private WebClient webClient;

    private WebClient client() {
        if (webClient == null) {
            webClient = WebClient.builder().baseUrl(baseUrl).build();
        }
        return webClient;
    }

    /**
     * 用快速模型分类意图，返回 simple 或 knowledge
     */
    @SuppressWarnings("unchecked")
    public Mono<String> classify(String userMessage) {
        Map<String, Object> request = Map.of(
                "model", "deepseek-v4-flash",
                "messages", List.of(
                        Map.of("role", "system", "content",
                                "你是一个意图分类器。将用户查询分为两类：\n"
                                + "- simple: 普通闲聊、问候、一般知识问答\n"
                                + "- knowledge: 需要检索企业知识库的专业问题、公司制度、技术规范、操作手册\n"
                                + "只回复 simple 或 knowledge，不要其他内容。"),
                        Map.of("role", "user", "content", userMessage)
                ),
                "max_tokens", 10,
                "temperature", 0
        );

        return client().post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Map.class)
                .map(resp -> {
                    List<Map<String, Object>> choices =
                            (List<Map<String, Object>>) resp.get("choices");
                    if (choices != null && !choices.isEmpty()) {
                        Map<String, Object> message =
                                (Map<String, Object>) choices.get(0).get("message");
                        String content = (String) message.get("content");
                        if (content != null) {
                            return content.trim().toLowerCase();
                        }
                    }
                    return "simple";
                })
                .defaultIfEmpty("simple")
                .doOnNext(r -> log.info("[IntentRouter] 分类结果: {} → {}", userMessage.length() > 30
                        ? userMessage.substring(0, 30) + "..."
                        : userMessage, r));
    }
}
