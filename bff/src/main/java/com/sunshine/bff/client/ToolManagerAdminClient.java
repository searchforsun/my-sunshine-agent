package com.sunshine.bff.client;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import com.sunshine.common.web.RemoteErrorMapper;
import reactor.core.publisher.Mono;

import java.util.Map;

@Slf4j
@Component
public class ToolManagerAdminClient {

    @Value("${tool-manager.base-url:http://localhost:8210}")
    private String baseUrl;

    private WebClient webClient;

    @PostConstruct
    void init() {
        webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                .build();
        log.info("[BFF] ToolManager Admin 客户端: baseUrl={}", baseUrl);
    }

    public Mono<Map<String, Object>> listSdkApplications() {
        return get("/api/admin/tools/sdk-applications");
    }

    public Mono<Map<String, Object>> syncSdkApplication(String id) {
        return webClient.post()
                .uri("/api/admin/tools/sdk-applications/{id}/sync", id)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> listMcpServers() {
        return get("/api/admin/mcp/servers");
    }

    public Mono<Map<String, Object>> createMcpServer(Map<String, Object> body) {
        return webClient.post()
                .uri("/api/admin/mcp/servers")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> importMcpServers(String rawJson) {
        return webClient.post()
                .uri("/api/admin/mcp/servers/import")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(rawJson)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<String> exportMcpServers() {
        return webClient.get()
                .uri("/api/admin/mcp/servers/export")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(String.class);
    }

    public Mono<Map<String, Object>> probeMcpServer(String id) {
        return webClient.post()
                .uri("/api/admin/mcp/servers/{id}/probe", id)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> patchMcpServer(String id, Map<String, Object> body) {
        return webClient.patch()
                .uri("/api/admin/mcp/servers/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> deleteMcpServer(String id) {
        return webClient.delete()
                .uri("/api/admin/mcp/servers/{id}", id)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> patchTool(String toolId, Map<String, Object> body) {
        return webClient.patch()
                .uri("/api/admin/tools/{toolId}", toolId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> getReactDefaultToolSet(String tenantId) {
        return webClient.get()
                .uri(uri -> {
                    var builder = uri.path("/api/admin/tools/sets/react-default");
                    if (StringUtils.hasText(tenantId)) {
                        builder.queryParam("tenantId", tenantId);
                    }
                    return builder.build();
                })
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> putReactDefaultToolSet(String tenantId, Map<String, Object> body) {
        return webClient.put()
                .uri(uri -> {
                    var builder = uri.path("/api/admin/tools/sets/react-default");
                    if (StringUtils.hasText(tenantId)) {
                        builder.queryParam("tenantId", tenantId);
                    }
                    return builder.build();
                })
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> getPlanWorkflowCriticalToolSet(String tenantId) {
        return webClient.get()
                .uri(uri -> {
                    var builder = uri.path("/api/admin/tools/sets/plan-workflow-critical");
                    if (StringUtils.hasText(tenantId)) {
                        builder.queryParam("tenantId", tenantId);
                    }
                    return builder.build();
                })
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> putPlanWorkflowCriticalToolSet(String tenantId, Map<String, Object> body) {
        return webClient.put()
                .uri(uri -> {
                    var builder = uri.path("/api/admin/tools/sets/plan-workflow-critical");
                    if (StringUtils.hasText(tenantId)) {
                        builder.queryParam("tenantId", tenantId);
                    }
                    return builder.build();
                })
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> getPlanWorkflowModePolicy(String tenantId) {
        return webClient.get()
                .uri(uri -> {
                    var builder = uri.path("/api/admin/tools/modes/plan-workflow");
                    if (StringUtils.hasText(tenantId)) {
                        builder.queryParam("tenantId", tenantId);
                    }
                    return builder.build();
                })
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> putPlanWorkflowModePolicy(String tenantId, Map<String, Object> body) {
        return webClient.put()
                .uri(uri -> {
                    var builder = uri.path("/api/admin/tools/modes/plan-workflow");
                    if (StringUtils.hasText(tenantId)) {
                        builder.queryParam("tenantId", tenantId);
                    }
                    return builder.build();
                })
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> catalog(String tenantId, boolean enabledOnly) {
        return webClient.get()
                .uri(uri -> {
                    var builder = uri.path("/api/tools/catalog")
                            .queryParam("enabledOnly", enabledOnly);
                    if (StringUtils.hasText(tenantId)) {
                        builder.queryParam("tenantId", tenantId);
                    }
                    return builder.build();
                })
                .header("x-tenant-id", StringUtils.hasText(tenantId) ? tenantId : "default")
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private Mono<Map<String, Object>> get(String path) {
        return webClient.get()
                .uri(path)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private Mono<? extends Throwable> toBizError(ClientResponse response) {
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .flatMap(body -> Mono.error(RemoteErrorMapper.fromBody(response.statusCode().value(), body)));
    }
}
