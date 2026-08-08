package com.sunshine.bff.client;

import com.sunshine.common.core.result.R;
import com.sunshine.common.tool.ToolCatalogEntry;
import com.sunshine.common.tool.admin.McpServerPatchRequest;
import com.sunshine.common.tool.admin.McpServerView;
import com.sunshine.common.tool.admin.SdkApplicationView;
import com.sunshine.common.tool.admin.ToolDefinitionView;
import com.sunshine.common.tool.admin.ToolPatchRequest;
import com.sunshine.common.tool.admin.ToolSetMemberAddRequest;
import com.sunshine.common.tool.admin.ToolSetMemberAddResult;
import com.sunshine.common.tool.admin.ToolSetMemberCriticalPatchRequest;
import com.sunshine.common.tool.admin.ToolSetMemberRemoveRequest;
import com.sunshine.common.tool.admin.ToolSetMembersPageResponse;
import com.sunshine.common.tool.admin.ToolSetPickerResponse;
import com.sunshine.common.web.RemoteErrorMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

@Slf4j
@Component
public class ToolManagerAdminClient {

    private final WebClient webClient;

    public ToolManagerAdminClient(WebClient.Builder builder) {
        this.webClient = builder
                .baseUrl("http://sunshine-tool-service")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                .build();
        log.info("[BFF] ToolManager Admin 客户端: baseUrl=http://sunshine-tool-service");
    }

    public Mono<R<List<SdkApplicationView>>> listSdkApplications() {
        return get("/api/admin/tools/sdk-applications", new ParameterizedTypeReference<>() {});
    }

    public Mono<R<Void>> syncSdkApplication(String id) {
        return webClient.post()
                .uri("/api/admin/tools/sdk-applications/{id}/sync", id)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<R<Void>>() {});
    }

    public Mono<R<List<McpServerView>>> listMcpServers() {
        return get("/api/admin/mcp/servers", new ParameterizedTypeReference<>() {});
    }

    public Mono<R<McpServerView>> createMcpServer(McpServerView body) {
        return webClient.post()
                .uri("/api/admin/mcp/servers")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<R<McpServerView>>() {});
    }

    public Mono<R<List<McpServerView>>> importMcpServers(String rawJson) {
        return webClient.post()
                .uri("/api/admin/mcp/servers/import")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(rawJson)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<R<List<McpServerView>>>() {});
    }

    public Mono<String> exportMcpServers() {
        return webClient.get()
                .uri("/api/admin/mcp/servers/export")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(String.class);
    }

    public Mono<R<Void>> probeMcpServer(String id) {
        return webClient.post()
                .uri("/api/admin/mcp/servers/{id}/probe", id)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<R<Void>>() {});
    }

    public Mono<R<McpServerView>> patchMcpServer(String id, McpServerPatchRequest body) {
        return webClient.patch()
                .uri("/api/admin/mcp/servers/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<R<McpServerView>>() {});
    }

    public Mono<R<Void>> deleteMcpServer(String id) {
        return webClient.delete()
                .uri("/api/admin/mcp/servers/{id}", id)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<R<Void>>() {});
    }

    public Mono<R<ToolDefinitionView>> patchTool(String toolId, ToolPatchRequest body) {
        return webClient.patch()
                .uri("/api/admin/tools/{toolId}", toolId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<R<ToolDefinitionView>>() {});
    }

    public Mono<R<ToolSetMembersPageResponse>> pageToolSetMembers(
            String kind, String tenantId, int page, int size, String q) {
        return webClient.get()
                .uri(uri -> {
                    var builder = uri.path("/api/admin/tools/sets/" + kind + "/members")
                            .queryParam("page", page)
                            .queryParam("size", size);
                    if (StringUtils.hasText(tenantId)) {
                        builder.queryParam("tenantId", tenantId);
                    }
                    if (StringUtils.hasText(q)) {
                        builder.queryParam("q", q);
                    }
                    return builder.build();
                })
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<R<ToolSetMembersPageResponse>>() {});
    }

    public Mono<R<ToolSetPickerResponse>> toolSetPicker(String kind, String tenantId, String q) {
        return webClient.get()
                .uri(uri -> {
                    var builder = uri.path("/api/admin/tools/sets/" + kind + "/picker");
                    if (StringUtils.hasText(tenantId)) {
                        builder.queryParam("tenantId", tenantId);
                    }
                    if (StringUtils.hasText(q)) {
                        builder.queryParam("q", q);
                    }
                    return builder.build();
                })
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<R<ToolSetPickerResponse>>() {});
    }

    public Mono<R<ToolSetMemberAddResult>> addToolSetMembers(
            String kind, String tenantId, ToolSetMemberAddRequest body) {
        return webClient.post()
                .uri(uri -> {
                    var builder = uri.path("/api/admin/tools/sets/" + kind + "/members:add");
                    if (StringUtils.hasText(tenantId)) {
                        builder.queryParam("tenantId", tenantId);
                    }
                    return builder.build();
                })
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<R<ToolSetMemberAddResult>>() {});
    }

    public Mono<R<Void>> removeToolSetMembers(
            String kind, String tenantId, ToolSetMemberRemoveRequest body) {
        return webClient.post()
                .uri(uri -> {
                    var builder = uri.path("/api/admin/tools/sets/" + kind + "/members:remove");
                    if (StringUtils.hasText(tenantId)) {
                        builder.queryParam("tenantId", tenantId);
                    }
                    return builder.build();
                })
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<R<Void>>() {});
    }

    public Mono<R<Void>> patchPlanWorkflowMemberCritical(
            String tenantId, String toolId, ToolSetMemberCriticalPatchRequest body) {
        return webClient.patch()
                .uri(uri -> {
                    var builder = uri.path("/api/admin/tools/sets/plan-workflow/members/" + toolId);
                    if (StringUtils.hasText(tenantId)) {
                        builder.queryParam("tenantId", tenantId);
                    }
                    return builder.build();
                })
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<R<Void>>() {});
    }

    public Mono<R<List<ToolCatalogEntry>>> catalog(String tenantId, boolean enabledOnly) {
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
                .bodyToMono(new ParameterizedTypeReference<R<List<ToolCatalogEntry>>>() {});
    }

    private <T> Mono<R<T>> get(String path, ParameterizedTypeReference<R<T>> type) {
        return webClient.get()
                .uri(path)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(type);
    }

    private Mono<? extends Throwable> toBizError(ClientResponse response) {
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .flatMap(body -> Mono.error(RemoteErrorMapper.fromBody(response.statusCode().value(), body)));
    }
}
