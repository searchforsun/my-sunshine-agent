package com.sunshine.llm.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 租户配额请求前校验（phase5 5.2.4）：调 orchestrator /api/usage/quota/check，结果本地 TTL 缓存。
 * fail-open：配额服务不可用时放行，避免配额抖动拖垮 LLM 调用。
 */
@Slf4j
@Component
public class QuotaCheckClient {

    private final WebClient webClient;
    private final LlmUsageProperties properties;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public QuotaCheckClient(LlmUsageProperties properties, ObjectMapper objectMapper, WebClient.Builder builder) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.webClient = builder
                .baseUrl(properties.getQuota().getOrchestratorBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(
                        HttpClient.create().responseTimeout(Duration.ofSeconds(2))))
                .build();
    }

    /** 返回 true=放行。缓存命中直接返回；过期或异常按 fail-open 放行。 */
    public boolean allowed(String tenantId, String model) {
        return check(tenantId, model).allowed();
    }

    public Outcome check(String tenantId, String model) {
        if (!properties.getQuota().isEnabled()) {
            return Outcome.ALLOWED;
        }
        String key = (tenantId == null || tenantId.isBlank() ? "default" : tenantId) + "|" + model;
        CacheEntry entry = cache.get(key);
        long now = System.currentTimeMillis();
        if (entry != null && entry.expireAtMillis > now) {
            return entry.outcome;
        }
        Outcome outcome = fetch(tenantId, model);
        cache.put(key, new CacheEntry(outcome, now + properties.getQuota().getCheckTtlSeconds() * 1000));
        return outcome;
    }

    private Outcome fetch(String tenantId, String model) {
        try {
            JsonNode root = webClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/api/usage/quota/check")
                            .queryParam("tenantId", tenantId == null || tenantId.isBlank() ? "default" : tenantId)
                            .queryParam("model", model == null ? "" : model)
                            .build())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp -> resp.createException()
                            .flatMap(e -> reactor.core.publisher.Mono.error(e)))
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(2));
            if (root == null) {
                return Outcome.ALLOWED;
            }
            JsonNode data = root.get("data");
            if (data == null || !data.has("allowed")) {
                return Outcome.ALLOWED;
            }
            boolean allowed = data.get("allowed").asBoolean(true);
            String code = data.has("code") ? data.get("code").asText("") : "";
            if (!allowed) {
                log.warn("[LlmQuota] 请求被配额拒绝 tenant={} model={} code={}",
                        tenantId, model, code);
            }
            return new Outcome(allowed, code);
        } catch (Exception e) {
            log.warn("[LlmQuota] 配额校验异常，fail-open 放行: {}", e.getMessage());
            return Outcome.ALLOWED;
        }
    }

    public record Outcome(boolean allowed, String code) {
        static final Outcome ALLOWED = new Outcome(true, "ok");
    }

    private record CacheEntry(Outcome outcome, long expireAtMillis) {
    }
}
