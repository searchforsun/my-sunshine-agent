package com.sunshine.orchestrator.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.catalog.AgentCatalogEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A2A 外部智能体 Client。
 * 消费外部 Agent Card，提交 task 并流式接收 SSE 响应，映射为 StreamToken。
 */
@Slf4j
@Component
public class ExternalAgentClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    private final WebClient webClient;

    public ExternalAgentClient() {
        this.webClient = WebClient.builder()
                .codecs(config -> config.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    /**
     * 调用外部 A2A 智能体，返回 Flux&lt;StreamToken&gt;。
     *
     * @param agent          AgentCatalogEntry（source=EXTERNAL）
     * @param query          用户任务描述
     * @param contextBlocks  上下文块
     * @return 流式 StreamToken（content 正文 + step 状态）
     */
    public Flux<StreamToken> invoke(AgentCatalogEntry agent, String query, List<String> contextBlocks) {
        String endpoint = resolveEndpoint(agent);
        String authHeader = resolveAuth(agent);
        String displayName = agent.displayName() != null ? agent.displayName() : agent.id();
        Map<String, Object> payload = buildA2aPayload(query, contextBlocks);

        String stepId = "external-" + agent.id();
        ProcessingStep startStep = ProcessingStep.running(stepId, "external", displayName + " 连接中…");
        ProcessingStep doneStep = ProcessingStep.done(stepId, "external",
                displayName, "外部智能体调用完成");

        return Mono.just(StreamToken.step(startStep))
                .concatWith(
                        webClient.post()
                                .uri(endpoint)
                                .header("Authorization", authHeader)
                                .header("Content-Type", "application/json")
                                .bodyValue(payload)
                                .retrieve()
                                .bodyToFlux(String.class)
                                .flatMap(line -> Mono.justOrEmpty(parseSseLine(line, stepId, displayName)))
                                .timeout(agentTimeout(agent))
                                .doOnError(e -> {
                                    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                                    log.warn("[ExternalAgentClient] agent={} error: {}", agent.id(), msg);
                                })
                                .onErrorResume(e -> Flux.empty())
                )
                .concatWithValues(StreamToken.step(doneStep));
    }

    private String resolveEndpoint(AgentCatalogEntry agent) {
        if (StringUtils.hasText(agent.endpointOverride())) {
            return agent.endpointOverride().strip();
        }
        if (StringUtils.hasText(agent.agentCardUrl())) {
            String url = agent.agentCardUrl().strip();
            if (url.endsWith("/.well-known/agent-card.json")) {
                url = url.substring(0, url.length() - "/.well-known/agent-card.json".length());
                if (url.endsWith("/")) {
                    url = url.substring(0, url.length() - 1);
                }
                return url + "/tasks/sendSubscribe";
            }
            return url;
        }
        throw new IllegalStateException("external agent missing endpoint: " + agent.id());
    }

    private String resolveAuth(AgentCatalogEntry agent) {
        if (!StringUtils.hasText(agent.authConfigJson()) || "{}".equals(agent.authConfigJson())) {
            return "";
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> auth = MAPPER.readValue(agent.authConfigJson(), Map.class);
            String type = (String) auth.get("type");
            if ("bearer".equalsIgnoreCase(type)) {
                String token = (String) auth.get("token");
                return "Bearer " + (token != null ? token : "");
            }
            if ("api-key".equalsIgnoreCase(type)) {
                String key = (String) auth.get("key");
                return "Api-Key " + (key != null ? key : "");
            }
            return "";
        } catch (Exception e) {
            log.warn("[ExternalAgentClient] parse auth config failed: {}", e.getMessage());
            return "";
        }
    }

    private Duration agentTimeout(AgentCatalogEntry agent) {
        return DEFAULT_TIMEOUT;
    }

    private Map<String, Object> buildA2aPayload(String query, List<String> contextBlocks) {
        StringBuilder textBuilder = new StringBuilder(query);
        if (contextBlocks != null && !contextBlocks.isEmpty()) {
            textBuilder.append("\n\n--- 上下文 ---\n");
            for (String block : contextBlocks) {
                textBuilder.append(block).append("\n");
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", textBuilder.toString()))));
        payload.put("acceptedOutputModes", List.of("text/plain"));
        return payload;
    }

    /**
     * 解析 A2A SSE 行 -> StreamToken。
     * A2A events: task, status-update, artifact-update
     */
    private StreamToken parseSseLine(String raw, String stepId, String displayName) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String line = raw.strip();
        if (!line.startsWith("data:")) {
            return null;
        }
        String json = line.substring(5).strip();
        if ("[DONE]".equals(json)) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> event = MAPPER.readValue(json, Map.class);
            return mapA2aEvent(event, stepId);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private StreamToken mapA2aEvent(Map<String, Object> event, String stepId) {
        String kind = (String) event.get("kind");
        if ("artifact-update".equals(kind)) {
            Object artifact = event.get("artifact");
            if (artifact instanceof Map) {
                Map<String, Object> art = (Map<String, Object>) artifact;
                Object parts = art.get("parts");
                if (parts instanceof List) {
                    List<Object> partList = (List<Object>) parts;
                    if (!partList.isEmpty() && partList.get(0) instanceof Map) {
                        Map<String, Object> part = (Map<String, Object>) partList.get(0);
                        if ("text".equals(part.get("kind"))) {
                            String text = (String) part.get("text");
                            if (text != null && !text.isEmpty()) {
                                return StreamToken.content(text);
                            }
                        }
                    }
                }
            }
        }
        if ("status-update".equals(kind)) {
            Object status = event.get("status");
            if (status instanceof Map) {
                Map<String, Object> st = (Map<String, Object>) status;
                String state = (String) st.get("state");
                if ("completed".equals(state) || "failed".equals(state) || "canceled".equals(state)) {
                    return new StreamToken(StreamToken.KIND_CONTENT_END, null, null, null, null, null);
                }
            }
        }
        return null;
    }
}
