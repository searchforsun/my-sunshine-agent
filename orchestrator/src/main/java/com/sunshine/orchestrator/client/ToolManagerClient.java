package com.sunshine.orchestrator.client;

import com.sunshine.common.core.result.R;
import com.sunshine.common.tool.ToolCatalogEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class ToolManagerClient {

    /** ReAct 工具路径：将异常转为模型可读文案；Workflow tool 节点须再判定并 fail */
    public static final String INVOKE_FAILURE_PREFIX = "工具调用失败:";

    private final WebClient webClient;

    public ToolManagerClient(WebClient.Builder builder) {
        this.webClient = builder.baseUrl("http://sunshine-tool-service").build();
        log.info("[ToolManagerClient] baseUrl=http://sunshine-tool-service");
    }

    public List<ToolCatalogEntry> fetchCatalog(String tenantId, boolean enabledOnly) {
        String effectiveTenant = tenantId == null || tenantId.isBlank() ? "default" : tenantId.strip();
        assertMayBlock("fetchCatalog");
        try {
            List<ToolCatalogEntry> entries = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/tools/catalog")
                            .queryParam("enabledOnly", enabledOnly)
                            .build())
                    .header("x-tenant-id", effectiveTenant)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<R<List<ToolCatalogEntry>>>() {})
                    .map(R::getData)
                    .onErrorResume(e -> {
                        log.warn("[ToolManagerClient] fetch catalog failed tenant={} enabledOnly={}: {}",
                                effectiveTenant, enabledOnly, e.getMessage());
                        return Mono.just(List.of());
                    })
                    .block();
            return entries != null ? entries : List.of();
        } catch (Exception e) {
            log.warn("[ToolManagerClient] fetch catalog error tenant={} enabledOnly={}: {}",
                    effectiveTenant, enabledOnly, e.getMessage());
            return List.of();
        }
    }

    public List<String> fetchChatDefault(String tenantId) {
        return fetchToolSetToolIds("chat", tenantId).toolIds();
    }

    public List<String> fetchTaskDefault(String tenantId) {
        return fetchToolSetToolIds("task", tenantId).toolIds();
    }

    public ToolSetToolIdsResponse fetchToolSetToolIds(String kind, String tenantId) {
        String effectiveTenant = tenantId == null || tenantId.isBlank() ? "default" : tenantId.strip();
        assertMayBlock("fetchToolSetToolIds:" + kind);
        try {
            ToolSetToolIdsResponse response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/tools/sets/" + kind + "/tool-ids")
                            .queryParam("tenantId", effectiveTenant)
                            .build())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<R<ToolSetToolIdsResponse>>() {})
                    .map(R::getData)
                    .onErrorResume(e -> {
                        log.warn("[ToolManagerClient] fetch {} tool-ids failed tenant={}: {}",
                                kind, effectiveTenant, e.getMessage());
                        return Mono.empty();
                    })
                    .block();
            if (response == null) {
                return new ToolSetToolIdsResponse(List.of());
            }
            List<String> toolIds = response.toolIds() != null ? List.copyOf(response.toolIds()) : List.of();
            return new ToolSetToolIdsResponse(toolIds);
        } catch (Exception e) {
            log.warn("[ToolManagerClient] fetch {} tool-ids error tenant={}: {}", kind, effectiveTenant, e.getMessage());
            return new ToolSetToolIdsResponse(List.of());
        }
    }

    /**
     * Reactor 非阻塞线程（如 reactor-http-epoll-*）禁止 {@code block()}：失败会被吞成空工具集，
     * MAIN ReAct 只剩 RAG/沙箱。调用方须在 {@code Schedulers.boundedElastic()} 上执行。
     */
    static void assertMayBlock(String op) {
        if (Schedulers.isInNonBlockingThread()) {
            throw new IllegalStateException(
                    "ToolManagerClient." + op + " must not block on non-blocking thread "
                            + Thread.currentThread().getName()
                            + "; use Schedulers.boundedElastic()");
        }
    }

    public Mono<String> invokeMono(String name, Map<String, String> params, String userId, String tenantId) {
        Map<String, Object> body = Map.of(
                "name", name,
                "params", params != null ? params : Map.of());
        String effectiveTenant = tenantId != null && !tenantId.isBlank() ? tenantId.strip() : "default";
        return webClient.post()
                .uri("/api/tools/invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-user-id", userId != null ? userId : "")
                .header("x-tenant-id", effectiveTenant)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<R<String>>() {})
                .map(R::getData)
                .onErrorResume(e -> {
                    log.warn("[ToolManagerClient] invoke {} failed: {}", name, e.getMessage());
                    return Mono.just(INVOKE_FAILURE_PREFIX + " " + e.getMessage());
                });
    }

    /**
     * Workflow tool 节点结构化调用：params 为 Map<String,Object>（含结构化 JSON 值），返回完整 JSON。
     * 失败时返回含 {@code error} 字段的 ObjectNode（保持 {@link #isInvokeFailureResult(JsonNode)} 兼容判断）；
     * 解析失败 fallback 为 {@code {output: text}}。
     */
    public Mono<com.fasterxml.jackson.databind.JsonNode> invokeJsonMono(
            String name, Map<String, Object> params, String userId, String tenantId) {
        Map<String, Object> body = Map.of(
                "name", name,
                "params", params != null ? params : Map.of());
        String effectiveTenant = tenantId != null && !tenantId.isBlank() ? tenantId.strip() : "default";
        return webClient.post()
                .uri("/api/tools/invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-user-id", userId != null ? userId : "")
                .header("x-tenant-id", effectiveTenant)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<R<String>>() {})
                .map(R::getData)
                .map(text -> parseInvokeResult(text))
                .onErrorResume(e -> {
                    log.warn("[ToolManagerClient] invokeJson {} failed: {}", name, e.getMessage());
                    com.fasterxml.jackson.databind.node.ObjectNode err = JSON_MAPPER.createObjectNode();
                    err.put("error", INVOKE_FAILURE_PREFIX + " " + e.getMessage());
                    return Mono.just(err);
                });
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static com.fasterxml.jackson.databind.JsonNode parseInvokeResult(String text) {
        if (text == null) {
            return JSON_MAPPER.createObjectNode();
        }
        try {
            com.fasterxml.jackson.databind.JsonNode parsed = JSON_MAPPER.readTree(text);
            return parsed != null ? parsed : JSON_MAPPER.createObjectNode();
        } catch (Exception e) {
            // 非 JSON 文本 fallback 为 {output: text}
            com.fasterxml.jackson.databind.node.ObjectNode wrapper = JSON_MAPPER.createObjectNode();
            wrapper.put("output", text);
            return wrapper;
        }
    }

    public static boolean isInvokeFailureResult(String result) {
        return result != null && result.startsWith(INVOKE_FAILURE_PREFIX);
    }

    /** 结构化调用失败判断：结果含 error 字段视为失败（与字符串版 {@link #isInvokeFailureResult(String)} 语义一致） */
    public static boolean isInvokeFailureResult(com.fasterxml.jackson.databind.JsonNode result) {
        return result != null && result.isObject() && result.has("error");
    }

    public Mono<ToolSummarizeOutputResponse> summarizeOutputMono(String toolName, String text) {
        Map<String, Object> body = Map.of(
                "toolName", toolName != null ? toolName : "",
                "text", text != null ? text : "");
        return webClient.post()
                .uri("/api/tools/summarize-output")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<R<ToolSummarizeOutputResponse>>() {})
                .map(R::getData);
    }

    public Mono<Map<String, String>> extractBindingsMono(String extractJson, String text) {
        Map<String, Object> body = Map.of(
                "extractJson", extractJson != null ? extractJson : "",
                "text", text != null ? text : "");
        return webClient.post()
                .uri("/api/tools/extract-bindings")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<R<Map<String, String>>>() {})
                .map(R::getData);
    }
}
