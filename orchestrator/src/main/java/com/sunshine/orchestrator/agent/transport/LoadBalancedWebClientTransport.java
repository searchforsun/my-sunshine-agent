package com.sunshine.orchestrator.agent.transport;

import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于 @LoadBalanced WebClient 的 AgentScope HttpTransport 实现。
 * 使 OpenAIChatModel 的 HTTP 请求走 Spring Cloud LoadBalancer → Nacos 服务发现，
 * 而非硬编码 host:port。
 * <p>
 * 使用方式：通过 OpenAIChatModel.Builder.httpTransport(this) 注入。
 * <p>
 * callSite（phase5 5.3）：Agent 角色调用点（chat/plan/worker/subagent），
 * 请求体 JSON 注入 {@code call_site}，供 llm-gateway 用量计量维度与模型路由消费。
 */
@Slf4j
public class LoadBalancedWebClientTransport implements HttpTransport {

    private final WebClient webClient;
    private final String callSite;
    /** 会话所选模型：注入 session_model 供网关路由策略耗尽后兜底；空不注入 */
    private final String sessionModel;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param loadBalancedBuilder 已注入 @LoadBalanced + @Primary 的 WebClient.Builder
     * @param serviceBaseUrl      服务名 URL（如 http://sunshine-llm-gateway）
     * @param callSite            Agent 角色调用点（chat/plan/worker/subagent），null 不注入
     */
    public LoadBalancedWebClientTransport(
            WebClient.Builder loadBalancedBuilder, String serviceBaseUrl, String callSite) {
        this(loadBalancedBuilder, serviceBaseUrl, callSite, null);
    }

    public LoadBalancedWebClientTransport(
            WebClient.Builder loadBalancedBuilder, String serviceBaseUrl,
            String callSite, String sessionModel) {
        this.webClient = loadBalancedBuilder
                .baseUrl(serviceBaseUrl)
                .build();
        this.callSite = callSite;
        this.sessionModel = sessionModel;
        log.info("[LoadBalancedWebClientTransport] serviceBaseUrl={} callSite={} sessionModel={}",
                serviceBaseUrl, callSite, sessionModel);
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
            Object body = withCallSite(request.getBody(), callSite, sessionModel);
            if (body != null) {
                spec.bodyValue(body);
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
        AtomicInteger sseCount = new AtomicInteger(0);
        try {
            WebClient.RequestBodySpec spec = webClient.method(method)
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON);
            request.getHeaders().forEach((k, v) -> {
                if (!"Content-Type".equalsIgnoreCase(k)) {
                    spec.header(k, v);
                }
            });
            Object body = withCallSite(request.getBody(), callSite, sessionModel);
            if (body != null) {
                spec.bodyValue(body);
            }
            return spec.retrieve()
                    .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                    .doOnSubscribe(s -> log.debug("[LoadBalancedWebClientTransport] SSE subscribed"))
                    .doOnNext(sse -> {
                        int n = sseCount.incrementAndGet();
                        if (n <= 3) {
                            log.debug("[LoadBalancedWebClientTransport] SSE #{}: id={} event={} dataLen={}",
                                    n, sse.id(), sse.event(), sse.data() != null ? sse.data().length() : 0);
                        }
                    })
                    .mapNotNull(ServerSentEvent::data)
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(data -> !"[DONE]".equals(data))
                    .doOnComplete(() -> log.debug("[LoadBalancedWebClientTransport] SSE completed: {} events", sseCount.get()))
                    .doOnError(e -> log.warn("[LoadBalancedWebClientTransport] SSE error: {}", e.getMessage()))
                    .doOnCancel(() -> log.warn("[LoadBalancedWebClientTransport] SSE cancelled after {} events", sseCount.get()));
        } catch (Exception e) {
            log.error("[LoadBalancedWebClientTransport] stream 初始化失败: {} {}", path, e.getMessage());
            return Flux.error(new HttpTransportException("stream 失败: " + path, e));
        }
    }

    @Override
    public void close() {
        // WebClient 无显式 close，资源由 Reactor 回收
    }

    /** 统一注入 call_site + session_model（AgentScope 请求体为 JSON 字符串）；非 JSON 原样返回 */
    static Object withCallSite(String body, String callSite, String sessionModel) {
        if (body == null || body.isBlank()
                || ((callSite == null || callSite.isBlank())
                && (sessionModel == null || sessionModel.isBlank()))) {
            return body;
        }
        try {
            ObjectNode node = (ObjectNode) objectMapper.readTree(body);
            if (callSite != null && !callSite.isBlank()) {
                node.put("call_site", callSite);
            }
            if (sessionModel != null && !sessionModel.isBlank()) {
                node.put("session_model", sessionModel.strip());
            }
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            log.debug("[LoadBalancedWebClientTransport] call_site/session_model 注入跳过（非 JSON body）: {}",
                    e.getMessage());
            return body;
        }
    }

    static Object withCallSite(String body, String callSite) {
        return withCallSite(body, callSite, null);
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
