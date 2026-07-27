package com.sunshine.orchestrator.context.l2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.client.LlmGatewayClient;
import com.sunshine.orchestrator.context.ContextProperties;
import com.sunshine.orchestrator.context.SessionTurn;
import com.sunshine.orchestrator.context.audit.ContextAuditService;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 每轮 completed 后静默抽取 L2 状态：LLM + Catalog {@code context.l2.extract} → 置信门禁 → Merger upsert。
 * 成功后触发轻量腐败/矛盾审计（异步、可防抖）。失败仅日志，不阻断用户路径。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class L2ExtractService {

    public static final String EXTRACT_PROMPT = "context.l2.extract";

    private static final Set<String> VALID_KINDS = Set.of(
            "profile", "preference", "goal", "agreement", "constraint", "fact", "decision",
            "reasoning", "option", "interim_conclusion", "topic");

    private static final ObjectMapper OM = new ObjectMapper();

    private final ContextProperties contextProperties;
    private final LlmGatewayClient llmGatewayClient;
    private final PromptCatalogHolder catalogHolder;
    private final L2StateStore l2StateStore;
    private final ContextAuditService contextAuditService;

    @Async
    public void extractAsync(
            String userId,
            String tenantId,
            String sourceMsgId,
            List<SessionTurn> history) {
        try {
            extract(userId, tenantId, sourceMsgId, history);
        } catch (Exception e) {
            log.warn("[ContextL2] 抽取失败 user={} msg={}: {}", userId, sourceMsgId, e.getMessage());
        }
    }

    public void extract(
            String userId,
            String tenantId,
            String sourceMsgId,
            List<SessionTurn> history) {
        if (!contextProperties.isEnabled() || !StringUtils.hasText(userId)) {
            return;
        }
        if (history == null || history.isEmpty()) {
            return;
        }
        String system = catalogHolder.requireText(EXTRACT_PROMPT);
        if (!StringUtils.hasText(system)) {
            log.warn("[ContextL2] missing catalog {}", EXTRACT_PROMPT);
            return;
        }
        String userPayload = buildExtractPayload(history);
        if (!StringUtils.hasText(userPayload)) {
            return;
        }
        String raw;
        try {
            raw = llmGatewayClient.complete(system, userPayload);
        } catch (Exception e) {
            log.warn("[ContextL2] extract LLM 失败: {}", e.getMessage());
            return;
        }
        List<L2ConflictMerger.Candidate> candidates = parseCandidates(raw);
        ContextProperties.L2 l2 = contextProperties.getL2();
        Instant now = Instant.now();
        int accepted = 0;
        for (L2ConflictMerger.Candidate c : candidates) {
            double minConf = minConfidenceFor(c.kind(), l2);
            if (c.confidence() < minConf) {
                log.debug("[ContextL2] drop low confidence kind={} key={} conf={}",
                        c.kind(), c.key(), c.confidence());
                continue;
            }
            l2StateStore.upsert(userId, tenantId, c, sourceMsgId, now);
            accepted++;
        }
        log.debug("[ContextL2] extracted user={} candidates={} accepted={}",
                userId, candidates.size(), accepted);
        if (accepted > 0 || !candidates.isEmpty()) {
            maybeAuditAfterExtract(userId, tenantId);
        }
    }

    private void maybeAuditAfterExtract(String userId, String tenantId) {
        ContextProperties.Maintenance m = contextProperties.getMaintenance();
        if (!m.isAuditEnabled() || !m.isAuditOnExtract()) {
            return;
        }
        try {
            contextAuditService.auditUserLightAsync(userId, tenantId);
        } catch (Exception e) {
            log.warn("[ContextL2] trigger audit failed user={}: {}", userId, e.getMessage());
        }
    }

    /** 按 kind 分级置信门禁：原 7 类 0.75，reasoning/option 0.7，interim_conclusion 0.6，topic 无门禁。 */
    static double minConfidenceFor(String kind, ContextProperties.L2 l2) {
        if (l2 == null) {
            l2 = new ContextProperties.L2();
        }
        return switch (L2ConflictMerger.normalizeKind(kind)) {
            case "reasoning", "option" -> l2.getReasoningMinConfidence();
            case "interim_conclusion" -> l2.getInterimConclusionMinConfidence();
            case "topic" -> 0.0;
            default -> l2.getMinConfidence();
        };
    }

    static String buildExtractPayload(List<SessionTurn> history) {
        // 取尾部若干轮，避免整段历史过长
        int from = Math.max(0, history.size() - 6);
        StringBuilder sb = new StringBuilder();
        sb.append("【本轮对话】\n");
        for (int i = from; i < history.size(); i++) {
            SessionTurn t = history.get(i);
            if (t == null || !StringUtils.hasText(t.content())) {
                continue;
            }
            sb.append(t.role()).append(": ").append(t.content().strip()).append('\n');
        }
        return sb.toString().strip();
    }

    static List<L2ConflictMerger.Candidate> parseCandidates(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        try {
            JsonNode root = OM.readTree(extractJsonArray(raw));
            if (root == null || !root.isArray()) {
                return List.of();
            }
            List<L2ConflictMerger.Candidate> out = new ArrayList<>();
            for (JsonNode node : root) {
                if (node == null || !node.isObject()) {
                    continue;
                }
                String kind = text(node, "kind");
                String key = text(node, "key");
                if (!StringUtils.hasText(key)) {
                    key = text(node, "stateKey");
                }
                String value = text(node, "value");
                if (!StringUtils.hasText(value)) {
                    value = text(node, "stateValue");
                }
                if (!StringUtils.hasText(kind) || !StringUtils.hasText(key) || !StringUtils.hasText(value)) {
                    continue;
                }
                kind = kind.strip().toLowerCase(Locale.ROOT);
                if (!VALID_KINDS.contains(kind)) {
                    continue;
                }
                double confidence = node.path("confidence").asDouble(Double.NaN);
                if (Double.isNaN(confidence)) {
                    continue;
                }
                out.add(new L2ConflictMerger.Candidate(kind, key.strip(), value.strip(), confidence));
            }
            return List.copyOf(out);
        } catch (Exception e) {
            return List.of();
        }
    }

    static String extractJsonArray(String raw) {
        String trimmed = raw.strip();
        if (trimmed.startsWith("```")) {
            int nl = trimmed.indexOf('\n');
            if (nl > 0) {
                trimmed = trimmed.substring(nl + 1);
            }
            int fence = trimmed.lastIndexOf("```");
            if (fence >= 0) {
                trimmed = trimmed.substring(0, fence);
            }
            trimmed = trimmed.strip();
        }
        int start = trimmed.indexOf('[');
        int end = trimmed.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return "";
        }
        String s = v.asText();
        return s != null ? s : "";
    }
}
