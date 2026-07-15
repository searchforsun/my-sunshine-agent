package com.sunshine.orchestrator.client;

import com.sunshine.common.core.result.R;
import com.sunshine.orchestrator.client.sandbox.CreateSessionRequest;
import com.sunshine.orchestrator.client.sandbox.CreateSessionResponse;
import com.sunshine.orchestrator.client.sandbox.ToolInvokeResponse;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

/**
 * sandbox-service HTTP 客户端 — 会话创建 / 工具调用 / 关闭。
 */
@Slf4j
@Component
public class SandboxClient {

    @Value("${sandbox-service.base-url:http://localhost:8226}")
    private String baseUrl;

    private WebClient webClient;

    @PostConstruct
    void init() {
        webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                .build();
        log.info("[SandboxClient] baseUrl={}", baseUrl);
    }

    public String createSession(CreateSessionRequest req) {
        CreateSessionResponse data = webClient.post()
                .uri("/api/sandbox/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<R<CreateSessionResponse>>() {})
                .map(R::getData)
                .block();
        if (data == null || !StringUtils.hasText(data.sessionId())) {
            throw new IllegalStateException("sandbox createSession returned empty sessionId");
        }
        return data.sessionId().strip();
    }

    public ToolInvokeResponse invoke(String sessionId, String toolName, Map<String, Object> body) {
        ToolInvokeResponse data = webClient.post()
                .uri("/api/sandbox/sessions/{id}/tools/{name}", sessionId, toolName)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body != null ? body : Map.of())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<R<ToolInvokeResponse>>() {})
                .map(R::getData)
                .block();
        if (data == null) {
            return new ToolInvokeResponse(false, "", null, Map.of());
        }
        return data;
    }

    public void closeSession(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        try {
            webClient.delete()
                    .uri("/api/sandbox/sessions/{id}", sessionId.strip())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<R<Void>>() {})
                    .block();
        } catch (Exception e) {
            log.warn("[SandboxClient] closeSession failed id={}: {}", sessionId, e.getMessage());
        }
    }
}
