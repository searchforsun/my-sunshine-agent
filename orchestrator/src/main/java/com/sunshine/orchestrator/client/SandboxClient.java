package com.sunshine.orchestrator.client;

import com.sunshine.common.core.result.R;
import com.sunshine.common.sandbox.CreateSessionRequest;
import com.sunshine.common.sandbox.CreateSessionResponse;
import com.sunshine.common.sandbox.FsContentDto;
import com.sunshine.common.sandbox.FsNodeDto;
import com.sunshine.common.sandbox.ToolInvokeResponse;
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
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * sandbox-service HTTP 客户端 — 会话创建 / 工具调用 / 关闭 / FS 浏览。
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
                .onStatus(HttpStatusCode::isError, this::toSandboxError)
                .bodyToMono(new ParameterizedTypeReference<R<CreateSessionResponse>>() {})
                .map(R::getData)
                .block();
        if (data == null || !StringUtils.hasText(data.sessionId())) {
            throw new IllegalStateException("sandbox createSession returned empty sessionId");
        }
        return data.sessionId().strip();
    }

    public boolean sessionAlive(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return false;
        }
        try {
            Map<String, Boolean> data = webClient.get()
                    .uri("/api/sandbox/sessions/{id}/alive", sessionId.strip())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<R<Map<String, Boolean>>>() {})
                    .map(R::getData)
                    .block();
            return data != null && Boolean.TRUE.equals(data.get("alive"));
        } catch (Exception e) {
            log.debug("[SandboxClient] sessionAlive failed id={}: {}", sessionId, e.getMessage());
            return false;
        }
    }

    public boolean sessionRunning(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return false;
        }
        try {
            Map<String, Boolean> data = webClient.get()
                    .uri("/api/sandbox/sessions/{id}/alive", sessionId.strip())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<R<Map<String, Boolean>>>() {})
                    .map(R::getData)
                    .block();
            return data != null && Boolean.TRUE.equals(data.get("running"));
        } catch (Exception e) {
            log.debug("[SandboxClient] sessionRunning failed id={}: {}", sessionId, e.getMessage());
            return false;
        }
    }

    public void stopSession(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        try {
            webClient.post()
                    .uri("/api/sandbox/sessions/{id}/stop", sessionId.strip())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<R<Void>>() {})
                    .block();
        } catch (Exception e) {
            log.warn("[SandboxClient] stopSession failed id={}: {}", sessionId, e.getMessage());
        }
    }

    public void startSession(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        try {
            webClient.post()
                    .uri("/api/sandbox/sessions/{id}/start", sessionId.strip())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, this::toSandboxError)
                    .bodyToMono(new ParameterizedTypeReference<R<Void>>() {})
                    .block();
        } catch (RuntimeException e) {
            log.warn("[SandboxClient] startSession failed id={}: {}", sessionId, e.getMessage());
            throw e;
        }
    }

    public FsNodeDto.FsListResponse listFs(String sessionId, String path) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/sandbox/sessions/{id}/fs")
                        .queryParam("path", path != null ? path : "/workspace")
                        .build(sessionId))
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toSandboxError)
                .bodyToMono(new ParameterizedTypeReference<R<FsNodeDto.FsListResponse>>() {})
                .map(R::getData)
                .block();
    }

    /** 递归列举目录下所有文件/目录路径（扁平化索引） */
    public FsNodeDto.FsIndexResponse listFsIndex(String sessionId, String path, int maxDepth) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/sandbox/sessions/{id}/fs/index")
                        .queryParam("path", path != null ? path : "/workspace")
                        .queryParam("maxDepth", maxDepth > 0 ? maxDepth : 64)
                        .build(sessionId))
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toSandboxError)
                .bodyToMono(new ParameterizedTypeReference<R<FsNodeDto.FsIndexResponse>>() {})
                .map(R::getData)
                .block();
    }

    public FsContentDto readFsContent(String sessionId, String path, int maxChars, int offset) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/sandbox/sessions/{id}/fs/content")
                        .queryParam("path", path)
                        .queryParam("maxChars", maxChars)
                        .queryParam("offset", offset)
                        .build(sessionId))
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toSandboxError)
                .bodyToMono(new ParameterizedTypeReference<R<FsContentDto>>() {})
                .map(R::getData)
                .block();
    }

    public ToolInvokeResponse invoke(String sessionId, String toolName, Map<String, Object> body) {
        return invoke(sessionId, toolName, body, null);
    }

    public ToolInvokeResponse invoke(
            String sessionId, String toolName, Map<String, Object> body, String invocationId) {
        var spec = webClient.post()
                .uri("/api/sandbox/sessions/{id}/tools/{name}", sessionId, toolName)
                .contentType(MediaType.APPLICATION_JSON);
        if (StringUtils.hasText(invocationId)) {
            spec = spec.header("x-sandbox-invocation-id", invocationId.strip());
        }
        ToolInvokeResponse data = spec
                .bodyValue(body != null ? body : Map.of())
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toSandboxError)
                .bodyToMono(new ParameterizedTypeReference<R<ToolInvokeResponse>>() {})
                .map(R::getData)
                .block();
        if (data == null) {
            return new ToolInvokeResponse(false, "", null, Map.of());
        }
        return data;
    }

    /** 取消进行中的沙箱工具调用（杀 docker Process / 置 host 协作标志） */
    public void cancelInvocation(String sessionId, String invocationId) {
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(invocationId)) {
            return;
        }
        webClient.post()
                .uri("/api/sandbox/sessions/{id}/invocations/{invocationId}/cancel",
                        sessionId.strip(), invocationId.strip())
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toSandboxError)
                .bodyToMono(new ParameterizedTypeReference<R<Map<String, Boolean>>>() {})
                .block();
    }

    public void mountSkill(String sessionId, String skillId, Map<String, String> skillFiles) {
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(skillId)) {
            throw new IllegalArgumentException("sessionId and skillId required");
        }
        webClient.put()
                .uri("/api/sandbox/sessions/{id}/skills/{skillId}", sessionId.strip(), skillId.strip())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(skillFiles != null ? skillFiles : Map.of())
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toSandboxError)
                .bodyToMono(new ParameterizedTypeReference<R<Void>>() {})
                .block();
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

    /** 将 sandbox R.msg 原样透出，避免只剩「400 Bad Request from POST …」 */
    private Mono<? extends Throwable> toSandboxError(ClientResponse response) {
        HttpStatusCode status = response.statusCode();
        return response.bodyToMono(new ParameterizedTypeReference<R<Object>>() {})
                .defaultIfEmpty(new R<>())
                .flatMap(r -> {
                    String msg = StringUtils.hasText(r.getMsg())
                            ? r.getMsg().strip()
                            : ("sandbox HTTP " + status.value());
                    return Mono.error(new IllegalStateException(msg));
                });
    }
}
