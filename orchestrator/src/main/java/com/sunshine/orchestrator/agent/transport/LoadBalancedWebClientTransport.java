package com.sunshine.orchestrator.agent.transport;

import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * 基于 @LoadBalanced WebClient 的 AgentScope HttpTransport 实现。
 * 使 OpenAIChatModel 的 HTTP 请求走 Spring Cloud LoadBalancer → Nacos 服务发现，
 * 而非硬编码 host:port。
 * <p>
 * 使用方式：通过 OpenAIChatModel.Builder.httpTransport(this) 注入。
 */
@Slf4j
public class LoadBalancedWebClientTransport implements HttpTransport {

    private final WebClient webClient;
    private final String serviceBaseUrl;

    /**
     * @param loadBalancedBuilder 已注入 @LoadBalanced + @Primary 的 WebClient.Builder
     * @param serviceBaseUrl      服务名 URL（如 http://sunshine-llm-gateway），仅用于路径提取基准
     */
    public LoadBalancedWebClientTransport(WebClient.Builder loadBalancedBuilder, String serviceBaseUrl) {
        this.serviceBaseUrl = serviceBaseUrl;
        this.webClient = loadBalancedBuilder
                .baseUrl(serviceBaseUrl)
                .build();
        log.info("[LoadBalancedWebClientTransport] serviceBaseUrl={}", serviceBaseUrl);
    }

    @Override
    public HttpResponse execute(HttpRequest request) throws HttpTransportException {
        String path = extractPath(request.getUrl());
        HttpMethod method = HttpMethod.valueOf(request.getMethod().toUpperCase());
        log.debug("[LoadBalancedWebClientTransport] execute {} {}", method, path);
        try {
            WebClient.RequestBodySpec spec = webClient.method(method)
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON);
            request.getHeaders().forEach((k, v) -> {
                if (!"Content-Type".equalsIgnoreCase(k)) {
                    spec.header(k, v);
                }
            });
            if (request.getBody() != null) {
                spec.bodyValue(request.getBody());
            }
            org.springframework.http.ResponseEntity<String> entity = spec
                    .retrieve()
                    .toEntity(String.class)
                    .timeout(Duration.ofSeconds(180))
                    .block();
            if (entity == null) {
                throw new HttpTransportException("execute 返回 null: " + path);
            }
            HttpResponse.Builder resp = HttpResponse.builder()
                    .statusCode(entity.getStatusCode().value())
                    .body(entity.getBody());
            entity.getHeaders().forEach((k, vals) -> {
                if (!vals.isEmpty()) {
                    resp.header(k, vals.get(0));
                }
            });
            HttpResponse built = resp.build();
            if (!built.isSuccessful()) {
                log.warn("[LoadBalancedWebClientTransport] execute {} → {} {}",
                        path, built.getStatusCode(), built.getBody());
            }
            return built;
        } catch (HttpTransportException e) {
            throw e;
        } catch (Exception e) {
            throw new HttpTransportException("execute 失败: " + path, e);
        }
    }

    @Override
    public Flux<String> stream(HttpRequest request) {
        String path = extractPath(request.getUrl());
        HttpMethod method = HttpMethod.valueOf(request.getMethod().toUpperCase());
        log.debug("[LoadBalancedWebClientTransport] stream {} {}", method, path);
        try {
            WebClient.RequestBodySpec spec = webClient.method(method)
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM);
            request.getHeaders().forEach((k, v) -> {
                if (!"Content-Type".equalsIgnoreCase(k) && !"Accept".equalsIgnoreCase(k)) {
                    spec.header(k, v);
                }
            });
            if (request.getBody() != null) {
                spec.bodyValue(request.getBody());
            }
            return spec.retrieve()
                    .bodyToFlux(String.class)
                    .filter(Objects::nonNull)
                    .filter(line -> line.startsWith("data:"))
                    .map(line -> {
                        String data = line.substring(5);
                        if (data.startsWith(" ")) {
                            data = data.substring(1);
                        }
                        return data;
                    })
                    .filter(data -> !"[DONE]".equals(data.trim()));
        } catch (Exception e) {
            log.error("[LoadBalancedWebClientTransport] stream 初始化失败: {} {}", path, e.getMessage());
            return Flux.error(new HttpTransportException("stream 失败: " + path, e));
        }
    }

    @Override
    public void close() {
        // WebClient 无显式 close，资源由 Reactor 回收
    }

    /**
     * 从 AgentScope HttpRequest 完整 URL 中提取 path+query。
     * 例如：http://sunshine-llm-gateway/v1/chat/completions → /v1/chat/completions
     */
    private String extractPath(String fullUrl) {
        try {
            URI uri = URI.create(fullUrl);
            String rawPath = uri.getRawPath();
            String rawQuery = uri.getRawQuery();
            if (rawQuery != null && !rawQuery.isEmpty()) {
                return rawPath + "?" + rawQuery;
            }
            return rawPath;
        } catch (Exception e) {
            log.warn("[LoadBalancedWebClientTransport] URL 解析失败，使用原值: {}", fullUrl);
            return fullUrl;
        }
    }
}
