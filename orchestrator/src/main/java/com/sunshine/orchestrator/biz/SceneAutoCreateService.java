package com.sunshine.orchestrator.biz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.client.BizSceneCatalogClient;
import com.sunshine.orchestrator.client.LlmGatewayClient;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * LLM 自动场景创建（authority §5.5b）：读/写路径 embedding 均未命中时，判断对话是否构成新业务场景，
 * 创建 {@code pending_review} 的 auto 场景并异步向量化。防污染闸门：≥2 轮 · auto 总数上限 ·
 * 10 分钟创建频率上限 · description 与既有场景同义重复抑制。
 * <p>仅写路径创建；读路径不创建。创建后立即入 embedding 索引缓存（后续读路径可命中），
 * 但 pending_review 不装载 Policy/任务板（无此权限）。
 */
@Slf4j
@Service
public class SceneAutoCreateService {

    private static final String CATALOG_ID = "context.biz-scene.auto-create";
    private static final String USER_SEPARATOR = "=== USER ===";
    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-z][a-z0-9_-]{2,48}$");
    /** 单次入参最近对话轮数上限（user+assistant 合计）。 */
    private static final int MAX_TURNS_INPUT = 3;

    private final BusinessContextProperties properties;
    private final BizSceneCatalogClient catalogClient;
    private final SceneEmbeddingService embeddingService;
    private final LlmGatewayClient llmGatewayClient;
    private final PromptCatalogHolder catalogHolder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 同 tenant 创建时间戳队列（10 分钟滑动窗口，内存态足够——orchestrator 为唯一创建方）。 */
    private final Map<String, Deque<Long>> createHistory = new ConcurrentHashMap<>();

    public SceneAutoCreateService(
            BusinessContextProperties properties,
            BizSceneCatalogClient catalogClient,
            SceneEmbeddingService embeddingService,
            LlmGatewayClient llmGatewayClient,
            PromptCatalogHolder catalogHolder) {
        this.properties = properties;
        this.catalogClient = catalogClient;
        this.embeddingService = embeddingService;
        this.llmGatewayClient = llmGatewayClient;
        this.catalogHolder = catalogHolder;
    }

    /**
     * 尝试为新业务域创建（或复用）场景；返回非空表示本轮获得场景锚定。
     * 可能返回：新建 auto 场景码 / 同义重复命中的既有场景码。
     */
    public Optional<String> tryCreate(String tenantId, String conversationId,
                                      List<String> lastUserTurns, List<String> lastAssistantTurns) {
        if (!properties.isEnabled() || !properties.getSceneAuto().isEnabled()) {
            return Optional.empty();
        }
        if (lastUserTurns == null || lastUserTurns.size() < 2) {
            log.debug("[SceneAuto] 轮数不足，跳过（user 轮次={}）", lastUserTurns == null ? 0 : lastUserTurns.size());
            return Optional.empty();
        }
        List<BizSceneCatalogClient.SceneIndexEntry> index = embeddingService.index();
        long autoCount = index.stream()
                .filter(e -> e != null && "auto".equals(e.source())
                        && ("active".equals(e.status()) || "pending_review".equals(e.status())))
                .count();
        if (autoCount >= properties.getSceneAuto().getMaxPending()) {
            log.info("[SceneAuto] tenant={} auto 场景数={} 已达上限 {}，跳过创建", tenantId, autoCount,
                    properties.getSceneAuto().getMaxPending());
            return Optional.empty();
        }
        if (!allowCreate(tenantId)) {
            log.info("[SceneAuto] tenant={} 创建频率受限，跳过", tenantId);
            return Optional.empty();
        }
        String description = buildCandidateDescription(lastUserTurns, lastAssistantTurns);
        if (!StringUtils.hasText(description)) {
            return Optional.empty();
        }
        // 同义重复抑制：候选描述与既有场景（active+pending）高度相似 → 复用既有，不新建
        Optional<SceneEmbeddingService.SceneMatch> dup =
                embeddingService.searchClosestAnyStatus(description);
        if (dup.isPresent() && dup.get().score() >= properties.getSceneAuto().getSimilarityThreshold()) {
            log.info("[SceneAuto] 与既有场景 {} 相似度 {} ≥ 阈值，复用不新建",
                    dup.get().bizScene(), String.format("%.2f", dup.get().score()));
            return Optional.of(dup.get().bizScene());
        }
        String judge = judge(description);
        JsonNode node = parseJson(judge);
        if (node == null || node.has("skip") && node.get("skip").asBoolean(false)) {
            log.info("[SceneAuto] LLM 判定不构成新场景或已 skip");
            return Optional.empty();
        }
        String code = node.path("scene").asText("").strip();
        String displayName = node.path("display_name").asText("").strip();
        String desc = node.path("description").asText("").strip();
        if (!CODE_PATTERN.matcher(code).matches() || !StringUtils.hasText(displayName)
                || !StringUtils.hasText(desc)) {
            log.warn("[SceneAuto] LLM 输出不满足格式 scene={} name={} desc={}", code, displayName, desc);
            return Optional.empty();
        }
        catalogClient.createAuto(code, displayName, desc, conversationId);
        recordCreate(tenantId);
        log.info("[SceneAuto] 创建 auto 场景 tenant={} scene={} name={} conv={}", tenantId, code, displayName, conversationId);
        // 异步向量化 + 入缓存（后续读路径可命中）
        embedAsync(code, desc);
        return Optional.of(code);
    }

    /** 异步 embedding 新场景 description → 推送 resource-manager → 更新本地索引缓存。 */
    @Async
    public void embedAsync(String bizScene, String description) {
        try {
            List<Float> vector = embeddingService.embed(description);
            if (vector == null || vector.isEmpty()) {
                return;
            }
            catalogClient.updateVector(bizScene, vector);
            embeddingService.upsertEntry(new BizSceneCatalogClient.SceneIndexEntry(
                    bizScene, description, vector, "pending_review", "auto", "default"));
        } catch (Exception e) {
            log.warn("[SceneAuto] embedAsync scene={} 失败: {}", bizScene, e.getMessage());
        }
    }

    /** 最近若干轮 user+assistant 摘要（尾部拼接，供 LLM 判断 + 重复抑制向量化）。 */
    String buildCandidateDescription(List<String> lastUserTurns, List<String> lastAssistantTurns) {
        StringBuilder sb = new StringBuilder();
        int turns = Math.min(MAX_TURNS_INPUT,
                Math.min(lastUserTurns.size(),
                        lastAssistantTurns != null ? lastAssistantTurns.size() : 0));
        for (int i = 0; i < turns; i++) {
            String u = lastUserTurns.get(lastUserTurns.size() - turns + i);
            String a = lastAssistantTurns.get(lastAssistantTurns.size() - turns + i);
            if (StringUtils.hasText(u)) {
                sb.append("用户: ").append(truncate(u, 200)).append('\n');
            }
            if (StringUtils.hasText(a)) {
                sb.append("助手: ").append(truncate(a, 200)).append('\n');
            }
        }
        return sb.toString().strip();
    }

    private String judge(String conversation) {
        String template = catalogHolder.snapshot().text(CATALOG_ID).map(String::strip).orElse(null);
        if (!StringUtils.hasText(template)) {
            log.warn("[SceneAuto] catalog missing id={}", CATALOG_ID);
            return "";
        }
        String[] parts = template.split(USER_SEPARATOR, 2);
        String system = parts[0].strip();
        String user = (parts.length > 1 ? parts[1] : parts[0])
                .replace("{conversation}", conversation)
                .replace("{scenes}", activeSceneList());
        try {
            return llmGatewayClient.complete(system, user);
        } catch (Exception e) {
            log.warn("[SceneAuto] LLM 判定失败: {}", e.getMessage());
            return "";
        }
    }

    /** 既有 active 场景码表（LLM 输入 §5.5b）：scene: display_name 一行一条；无则占位「无」。 */
    private String activeSceneList() {
        List<BizSceneCatalogClient.SceneIndexEntry> index = embeddingService.index();
        StringBuilder sb = new StringBuilder();
        for (BizSceneCatalogClient.SceneIndexEntry entry : index) {
            if (entry == null || !StringUtils.hasText(entry.bizScene())
                    || !"active".equals(entry.status())) {
                continue;
            }
            sb.append(entry.bizScene());
            if (StringUtils.hasText(entry.description())) {
                sb.append(": ").append(entry.description());
            }
            sb.append('\n');
        }
        return sb.length() == 0 ? "无" : sb.toString().strip();
    }

    private boolean allowCreate(String tenantId) {
        long now = System.currentTimeMillis();
        long window = 10 * 60 * 1000L;
        Deque<Long> deque = createHistory.computeIfAbsent(tenantId, k -> new ArrayDeque<>());
        synchronized (deque) {
            while (!deque.isEmpty() && deque.peekFirst() < now - window) {
                deque.pollFirst();
            }
            return deque.size() < properties.getSceneAuto().getCreateRateLimit();
        }
    }

    private void recordCreate(String tenantId) {
        createHistory.computeIfAbsent(tenantId, k -> new ArrayDeque<>()).addLast(System.currentTimeMillis());
    }

    private static JsonNode parseJson(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            int start = raw.indexOf('{');
            if (start < 0) {
                return null;
            }
            int end = raw.lastIndexOf('}');
            if (end < start) {
                return null;
            }
            return new ObjectMapper().readTree(raw.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
