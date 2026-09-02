package com.sunshine.orchestrator.client;

import com.sunshine.common.core.result.R;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 业务场景 Lab active 码闭集缓存（K2）：biz_scene 解析的合法码校验。
 * <p>active 码与 active Policy 均为低频数据：启动预加载 + 定时刷新，请求路径只读 volatile
 * 缓存，避免在 reactor 非阻塞线程上 block()（被 Reactor 拒绝后会清空闭集）。
 */
@Slf4j
@Component
public class BizSceneCatalogClient {

    /** Policy 缓存行（authority §4.2）：rulesJson 为规则提示词文本（Lab 逐条模型）。 */
    public record CachedPolicy(
            String tenantId,
            String bizScene,
            int version,
            String rulesJson,
            Instant effectiveFrom,
            Instant effectiveTo) {
    }

    /** 场景 embedding 检索索引项（authority §2.1b）：description + 向量 + 状态/来源。 */
    public record SceneIndexEntry(
            String bizScene,
            String description,
            List<Float> vector,
            String status,
            String source,
            String tenantId) {
    }

    private final WebClient webClient;
    private volatile Set<String> activeCodes = Set.of();
    private volatile List<CachedPolicy> activePolicies = List.of();

    public BizSceneCatalogClient(WebClient.Builder builder) {
        this.webClient = builder.baseUrl("http://sunshine-resource-manager").build();
    }

    @PostConstruct
    void warmUp() {
        refresh();
    }

    /** 定时刷新 active 码闭集与 Policy 快照（低频数据，无需高频率） */
    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void scheduledRefresh() {
        refresh();
    }

    /** 只读缓存快照；refresh 由启动预加载与定时任务负责，不在请求路径触发 */
    public Set<String> activeCodes() {
        return activeCodes;
    }

    /** 全租户（含 * 平台默认）active Policy 快照；请求路径零阻塞只读 */
    public List<CachedPolicy> activePolicies() {
        return activePolicies;
    }

    /** 拉取失败保留旧缓存（瞬时故障不清空闭集），仅记录告警 */
    public synchronized void refresh() {
        try {
            List<String> codes = webClient.get()
                    .uri("/api/biz-scenes/active-codes")
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<R<List<String>>>() {})
                    .map(R::getData)
                    .block(Duration.ofSeconds(5));
            if (codes != null) {
                this.activeCodes = Set.copyOf(codes);
            }
        } catch (Exception e) {
            log.warn("[BizSceneCatalogClient] fetch active codes error: {}", e.getMessage());
        }
        try {
            List<PolicyDto> policies = webClient.get()
                    .uri("/api/biz-scenes/policies/active")
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<R<List<PolicyDto>>>() {})
                    .map(R::getData)
                    .block(Duration.ofSeconds(5));
            if (policies != null) {
                this.activePolicies = policies.stream()
                        .filter(p -> p != null && StringUtils.hasText(p.bizScene()))
                        .map(p -> new CachedPolicy(
                                p.tenantId() != null ? p.tenantId() : "default",
                                p.bizScene(), p.version(), p.rulesJson(),
                                p.effectiveFrom(), p.effectiveTo()))
                        .toList();
            }
        } catch (Exception e) {
            log.warn("[BizSceneCatalogClient] fetch active policies error: {}", e.getMessage());
        }
    }

    /** 与 resource-manager BizScenePolicyView 对齐的反序列化行 */
    public record PolicyDto(
            Long policyId,
            String tenantId,
            String bizScene,
            int version,
            String status,
            String rulesJson,
            Instant effectiveFrom,
            Instant effectiveTo,
            Instant updatedAt) {
    }

    /** 与 resource-manager BizSceneEmbeddingItem 对齐的反序列化行 */
    public record EmbeddingIndexDto(
            String bizScene,
            String displayName,
            String description,
            String descriptionVector,
            String status,
            String source,
            String tenantId) {
    }

    /** 场景 embedding 检索索引全量拉取（向量为 JSON float[] 字符串，空串/空列 = 待回填）。 */
    public List<SceneIndexEntry> embeddingIndex() {
        try {
            List<EmbeddingIndexDto> items = webClient.get()
                    .uri("/api/biz-scenes/embedding-index")
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<R<List<EmbeddingIndexDto>>>() {})
                    .map(R::getData)
                    .block(Duration.ofSeconds(5));
            if (items == null) {
                return List.of();
            }
            return items.stream()
                    .filter(i -> i != null && i.bizScene() != null)
                    .map(i -> new SceneIndexEntry(
                            i.bizScene(), i.description() != null ? i.description() : "",
                            parseVector(i.descriptionVector()), i.status(), i.source(),
                            i.tenantId() != null ? i.tenantId() : "default"))
                    .toList();
        } catch (Exception e) {
            log.warn("[BizSceneCatalogClient] fetch embedding-index error: {}", e.getMessage());
            return null;
        }
    }

    /** 场景 description 向量回填（orchestrator 计算后推送）。 */
    public void updateVector(String bizScene, List<Float> vector) {
        try {
            webClient.put()
                    .uri("/api/biz-scenes/{scene}/vector", bizScene)
                    .bodyValue(Map.of("vector", vector))
                    .retrieve()
                    .bodyToMono(R.class)
                    .block(Duration.ofSeconds(5));
        } catch (Exception e) {
            log.warn("[BizSceneCatalogClient] updateVector scene={} error: {}", bizScene, e.getMessage());
        }
    }

    /** LLM 自动创建 auto 场景（初始 pending_review + 溯源会话）。 */
    public void createAuto(String bizScene, String displayName, String description, String sourceConversationId) {
        try {
            webClient.post()
                    .uri("/api/biz-scenes")
                    .bodyValue(Map.of(
                            "bizScene", bizScene,
                            "displayName", displayName,
                            "description", description,
                            "source", "auto",
                            "sourceConversationId", sourceConversationId))
                    .retrieve()
                    .bodyToMono(R.class)
                    .block(Duration.ofSeconds(5));
        } catch (Exception e) {
            log.warn("[BizSceneCatalogClient] createAuto scene={} error: {}", bizScene, e.getMessage());
        }
    }

    /** 审核操作：approve → status=active+approvedBy；reject → status=rejected。 */
    public void review(String bizScene, String status, String approvedBy) {
        try {
            webClient.put()
                    .uri("/api/biz-scenes/{scene}", bizScene)
                    .bodyValue(Map.of("status", status, "approvedBy", approvedBy))
                    .retrieve()
                    .bodyToMono(R.class)
                    .block(Duration.ofSeconds(5));
        } catch (Exception e) {
            log.warn("[BizSceneCatalogClient] review scene={} status={} error: {}", bizScene, status, e.getMessage());
        }
    }

    private static List<Float> parseVector(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            List<Number> numbers = new ObjectMapper().readValue(json, new TypeReference<List<Number>>() {});
            return numbers.stream().map(Number::floatValue).toList();
        } catch (Exception e) {
            log.warn("[BizSceneCatalogClient] parse vector failed: {}", e.getMessage());
            return List.of();
        }
    }
}
