package com.sunshine.orchestrator.client;

import com.sunshine.common.core.result.R;
import com.sunshine.common.tool.ToolCatalogEntry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${tool-manager.base-url:http://localhost:8210}")
    private String baseUrl;

    private WebClient webClient;

    @PostConstruct
    void init() {
        webClient = WebClient.builder().baseUrl(baseUrl).build();
        log.info("[ToolManagerClient] baseUrl={}", baseUrl);
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

    public List<String> fetchReactDefault(String tenantId) {
        return fetchToolSetToolIds("react-default", tenantId).toolIds();
    }

    public List<String> fetchPlanWorkflow(String tenantId) {
        return fetchToolSetToolIds("plan-workflow", tenantId).toolIds();
    }

    public List<String> fetchPlanWorkflowCritical(String tenantId) {
        return fetchToolSetToolIds("plan-workflow", tenantId).criticalToolIds();
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
                return new ToolSetToolIdsResponse(List.of(), List.of());
            }
            List<String> toolIds = response.toolIds() != null ? List.copyOf(response.toolIds()) : List.of();
            List<String> critical = response.criticalToolIds() != null
                    ? List.copyOf(response.criticalToolIds())
                    : List.of();
            return new ToolSetToolIdsResponse(toolIds, critical);
        } catch (Exception e) {
            log.warn("[ToolManagerClient] fetch {} tool-ids error tenant={}: {}", kind, effectiveTenant, e.getMessage());
            return new ToolSetToolIdsResponse(List.of(), List.of());
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

    public Mono<String> invokeMono(String name, Map<String, String> params) {
        Map<String, Object> body = Map.of(
                "name", name,
                "params", params != null ? params : Map.of());
        return webClient.post()
                .uri("/api/tools/invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<R<String>>() {})
                .map(R::getData)
                .onErrorResume(e -> {
                    log.warn("[ToolManagerClient] invoke {} failed: {}", name, e.getMessage());
                    return Mono.just(INVOKE_FAILURE_PREFIX + " " + e.getMessage());
                });
    }

    public static boolean isInvokeFailureResult(String result) {
        return result != null && result.startsWith(INVOKE_FAILURE_PREFIX);
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
