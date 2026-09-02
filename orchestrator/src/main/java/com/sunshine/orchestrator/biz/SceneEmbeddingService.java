package com.sunshine.orchestrator.biz;

import com.sunshine.orchestrator.client.BizSceneCatalogClient;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 业务场景 embedding 回退（authority §2.1b/§2.1c/§5.5）：查询/正文向量化 + 与场景 description 余弦匹配。
 * <p>索引缓存：启动预加载 + 定时刷新 resource-manager {@code embedding-index}；active 场景缺向量的懒回填
 * （embed description → 推送回 resource-manager → 更新缓存）。请求路径只读缓存，零阻塞。
 */
@Slf4j
@Service
public class SceneEmbeddingService {

    public record SceneMatch(String bizScene, double score) {
    }

    private final BusinessContextProperties properties;
    private final BizSceneCatalogClient catalogClient;

    private volatile List<BizSceneCatalogClient.SceneIndexEntry> index = List.of();
    private WebClient webClient;
    private final AtomicBoolean backfilling = new AtomicBoolean(false);

    public SceneEmbeddingService(BusinessContextProperties properties, BizSceneCatalogClient catalogClient) {
        this.properties = properties;
        this.catalogClient = catalogClient;
    }

    @PostConstruct
    void warmUp() {
        refreshIndex();
    }

    /** 定时刷新场景索引 + 懒回填缺向量场景（低频数据；embedding 仅在缺向量时触发）。 */
    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void scheduledRefresh() {
        refreshIndex();
    }

    public synchronized void refreshIndex() {
        List<BizSceneCatalogClient.SceneIndexEntry> fresh = catalogClient.embeddingIndex();
        if (fresh == null) {
            return; // 拉取失败保留旧缓存
        }
        this.index = fresh;
        backfillMissingVectors();
    }

    /** 场景 embedding 检索（authority §2.1c）：active + pending_review（仅嵌入检索可用），
     *  取最高余弦分 ≥ minScore 者。disabled/rejected/auto_cleaned 不进入检索。 */
    public Optional<SceneMatch> search(String text) {
        if (!enabled() || !StringUtils.hasText(text)) {
            return Optional.empty();
        }
        List<Float> queryVector = embed(text);
        if (queryVector == null || queryVector.isEmpty()) {
            return Optional.empty();
        }
        return searchVector(queryVector).stream().findFirst();
    }

    /** 与既有场景（active + pending_review）的最高相似度：LLM 自动创建前做重复抑制（authority §5.5b）。 */
    public Optional<SceneMatch> searchClosestAnyStatus(String text) {
        List<Float> queryVector = embed(text);
        if (queryVector == null || queryVector.isEmpty()) {
            return Optional.empty();
        }
        return searchVector(queryVector).stream().findFirst();
    }

    /** 缓存内余弦匹配（供测试直入向量）：仅 active + pending_review 进入检索（§2.1c）。 */
    List<SceneMatch> searchVector(List<Float> queryVector) {
        List<SceneMatch> matches = new ArrayList<>();
        for (BizSceneCatalogClient.SceneIndexEntry entry : index) {
            if (entry == null || !StringUtils.hasText(entry.bizScene())
                    || entry.vector() == null || entry.vector().isEmpty()
                    || !StringUtils.hasText(entry.description())) {
                continue;
            }
            if (!"active".equals(entry.status()) && !"pending_review".equals(entry.status())) {
                continue;
            }
            double score = cosine(queryVector, entry.vector());
            if (score >= properties.getSceneEmbedding().getMinScore()) {
                matches.add(new SceneMatch(entry.bizScene(), score));
            }
        }
        matches.sort(Comparator.comparingDouble(SceneMatch::score).reversed());
        return matches;
    }

    /**
     * 向量化文本；失败返回 null（调用方回退下一步，不阻塞主链路）。
     * 调用线程可能是 reactor-http 事件循环（读路径 resolveBizScene 在其上同步执行），
     * 直接在事件循环线程 block 会触发 Reactor NonBlocking 检查抛异常；
     * 故 HTTP 调用统一调度到 boundedElastic 线程执行，调用线程经 Future.get 同步等待。
     */
    public List<Float> embed(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        if (!StringUtils.hasText(properties.getSceneEmbedding().getApiKey())) {
            log.warn("[SceneEmbedding] api-key 未配置，embedding 回退禁用");
            return null;
        }
        try {
            String body = "{\"model\":\"" + properties.getSceneEmbedding().getModel()
                    + "\",\"input\":{\"texts\":[\"" + escape(text) + "\"]},"
                    + "\"parameters\":{\"text_type\":\"query\"}}";
            return Mono.fromCallable(() -> client().post()
                            .bodyValue(body)
                            .retrieve()
                            .bodyToMono(String.class)
                            .map(SceneEmbeddingService::parseEmbedding)
                            .onErrorResume(e -> Mono.empty())
                            .block(Duration.ofSeconds(20)))
                    .subscribeOn(Schedulers.boundedElastic())
                    .toFuture()
                    .get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[SceneEmbedding] embed 失败: {}", e.getMessage());
            return null;
        }
    }

    /** 自动创建后立即入缓存（读路径后续可命中）。 */
    public void upsertEntry(BizSceneCatalogClient.SceneIndexEntry entry) {
        List<BizSceneCatalogClient.SceneIndexEntry> next = new ArrayList<>(this.index);
        next.removeIf(e -> e.bizScene().equals(entry.bizScene()));
        next.add(entry);
        this.index = List.copyOf(next);
    }

    /** 索引缓存只读快照（防污染计数用）。 */
    public List<BizSceneCatalogClient.SceneIndexEntry> index() {
        return index;
    }

    public boolean enabled() {
        return properties.isEnabled() && properties.getSceneEmbedding().isEnabled();
    }

    /** active 场景缺向量懒回填：embed description → 推送 resource-manager → 更新缓存（防并发重复回填）。 */
    private void backfillMissingVectors() {
        if (!enabled() || !backfilling.compareAndSet(false, true)) {
            return;
        }
        try {
            for (BizSceneCatalogClient.SceneIndexEntry entry : index) {
                if (entry == null || entry.vector() != null && !entry.vector().isEmpty()) {
                    continue;
                }
                if (!"active".equals(entry.status()) && !"pending_review".equals(entry.status())) {
                    continue;
                }
                if (!StringUtils.hasText(entry.description())) {
                    continue;
                }
                List<Float> vector = embed(entry.description());
                if (vector == null || vector.isEmpty()) {
                    continue;
                }
                catalogClient.updateVector(entry.bizScene(), vector);
                upsertEntry(new BizSceneCatalogClient.SceneIndexEntry(
                        entry.bizScene(), entry.description(), vector,
                        entry.status(), entry.source(), entry.tenantId()));
                log.info("[SceneEmbedding] 回填向量 scene={}", entry.bizScene());
            }
        } catch (Exception e) {
            log.warn("[SceneEmbedding] backfill 失败: {}", e.getMessage());
        } finally {
            backfilling.set(false);
        }
    }

    private WebClient client() {
        if (webClient == null) {
            webClient = WebClient.builder()
                    .baseUrl(properties.getSceneEmbedding().getBaseUrl())
                    .defaultHeader("Authorization", "Bearer " + properties.getSceneEmbedding().getApiKey())
                    .build();
        }
        return webClient;
    }

    /** 解析 DashScope 响应：{"output":{"embeddings":[{"embedding":[...]}]}}。 */
    @SuppressWarnings("unchecked")
    private static List<Float> parseEmbedding(String raw) {
        try {
            String body = raw.substring(raw.indexOf("{"));
            Map<String, Object> root = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(body, Map.class);
            Map<String, Object> output = (Map<String, Object>) root.get("output");
            List<Map<String, Object>> embeddings =
                    (List<Map<String, Object>>) output.get("embeddings");
            List<Number> vector = (List<Number>) embeddings.get(0).get("embedding");
            return vector.stream().map(Number::floatValue).toList();
        } catch (Exception e) {
            log.warn("[SceneEmbedding] 解析响应失败: {}", e.getMessage());
            return null;
        }
    }

    /** 与 rag-service 一致的余弦相似度（向量等长校验，不等长返回 0）。 */
    public static double cosine(List<Float> a, List<Float> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty() || a.size() != b.size()) {
            return 0d;
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.size(); i++) {
            double x = a.get(i), y = b.get(i);
            dot += x * y;
            na += x * x;
            nb += y * y;
        }
        if (na == 0 || nb == 0) {
            return 0d;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }
}
