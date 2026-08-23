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
import java.util.regex.Pattern;

/**
 * 每轮 completed 后静默抽取 L2 状态：LLM + Catalog {@code context.memory.extract}（scope 参数化）→ 置信门禁 → Merger upsert。
 * todo 类叠加 v22 门禁（key 场景化 / background 必填 / value 非布尔孤值）；其他 kind 不强弃，兼容 chat 现状。
 * 成功后触发轻量腐败/矛盾审计（异步、可防抖）。失败仅日志，不阻断用户路径。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class L2ExtractService {

    public static final String EXTRACT_PROMPT = "context.memory.extract";

    /** v22：key 必须 {domain}.{facet}（todo 类强制）。 */
    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$");

    /** v22：布尔孤值禁止（todo 类强制）。 */
    private static final Set<String> BOOLEAN_LONE_VALUES = Set.of("true", "false", "yes", "no", "1", "0");

    private static final Set<String> VALID_KINDS = Set.of(
            "profile", "preference", "goal", "agreement", "constraint", "fact", "decision",
            "reasoning", "option", "interim_conclusion", "topic", "todo");

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
            List<SessionTurn> history,
            Instant msgAt) {
        try {
            extract(userId, tenantId, sourceMsgId, history, msgAt);
        } catch (Exception e) {
            log.warn("[ContextL2] 抽取失败 user={} msg={}: {}", userId, sourceMsgId, e.getMessage());
        }
    }

    public void extract(
            String userId,
            String tenantId,
            String sourceMsgId,
            List<SessionTurn> history) {
        extract(userId, tenantId, sourceMsgId, history, null);
    }

    public void extract(
            String userId,
            String tenantId,
            String sourceMsgId,
            List<SessionTurn> history,
            Instant msgAt) {
        if (!StringUtils.hasText(userId)) {
            return;
        }
        runExtract("user", userId, null, tenantId, sourceMsgId, history, msgAt);
    }

    /**
     * workspace scope 抽取：与 extract 共用同一参数化 prompt（scope=workspace）/ parseCandidates / 置信门禁，
     * 仅落库走 workspace 维度。workspaceId 空 → 直接返回；失败仅日志。
     */
    public void extractWorkspace(
            String workspaceId,
            String tenantId,
            String sourceMsgId,
            List<SessionTurn> history) {
        extractWorkspace(workspaceId, tenantId, sourceMsgId, history, null);
    }

    public void extractWorkspace(
            String workspaceId,
            String tenantId,
            String sourceMsgId,
            List<SessionTurn> history,
            Instant msgAt) {
        if (!StringUtils.hasText(workspaceId)) {
            return;
        }
        runExtract("workspace", null, workspaceId, tenantId, sourceMsgId, history, msgAt);
    }

    private void runExtract(
            String scope,
            String userId,
            String workspaceId,
            String tenantId,
            String sourceMsgId,
            List<SessionTurn> history,
            Instant msgAt) {
        if (!contextProperties.isEnabled() || history == null || history.isEmpty()) {
            return;
        }
        String system = buildSystemPrompt(catalogHolder.requireText(EXTRACT_PROMPT), scope);
        if (!StringUtils.hasText(system)) {
            log.warn("[ContextL2] missing catalog {}", EXTRACT_PROMPT);
            return;
        }
        String userPayload = buildExtractPayload(history, existingTodoHints(userId, workspaceId, tenantId));
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
        // 落库时钟以消息时间为准：避免异步抽取乱序时旧消息覆盖新消息已 void 的状态
        Instant now = msgAt != null ? msgAt : Instant.now();
        int accepted = 0;
        for (L2ConflictMerger.Candidate c : candidates) {
            double minConf = minConfidenceFor(c.kind(), l2);
            if (c.confidence() < minConf) {
                log.debug("[ContextL2] drop low confidence kind={} key={} conf={}",
                        c.kind(), c.key(), c.confidence());
                continue;
            }
            if (workspaceId != null) {
                l2StateStore.upsertWorkspace(workspaceId, tenantId, c, sourceMsgId, now);
            } else {
                l2StateStore.upsert(userId, tenantId, c, sourceMsgId, now);
            }
            accepted++;
        }
        log.debug("[ContextL2] extracted scope={} candidates={} accepted={}",
                workspaceId != null ? "workspace:" + workspaceId : "user:" + userId,
                candidates.size(), accepted);
        // workspace scope 无 user 维度，不触发 user 级审计
        if (StringUtils.hasText(userId) && (accepted > 0 || !candidates.isEmpty())) {
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
            case "todo" -> l2.getMinConfidence();
            default -> l2.getMinConfidence();
        };
    }

    /** Catalog 正文 {scope} 占位替换：user / workspace 中文释义由正文自带。 */
    static String buildSystemPrompt(String catalogText, String scope) {
        if (!StringUtils.hasText(catalogText)) {
            return "";
        }
        return catalogText.replace("{scope}", scope);
    }

    /** 既有 active todo 的 key 参照（scope 维度），让 LLM 在完成/取消时沿用原 key 产出 status=void，避免 key 漂移导致失效落空。 */
    private String existingTodoHints(String userId, String workspaceId, String tenantId) {
        List<UserContextStateEntity> active = workspaceId != null
                ? l2StateStore.listInjectableWorkspace(workspaceId, tenantId, Instant.now())
                : l2StateStore.listInjectable(userId, tenantId, Instant.now());
        if (active == null || active.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【既有待办（仅作 key 参照；完成/取消时输出 status=void 且 key 必须与下列之一完全一致）】\n");
        for (UserContextStateEntity e : active) {
            if (e == null || !"todo".equals(e.getKind()) || !StringUtils.hasText(e.getStateKey())) {
                continue;
            }
            sb.append("- ").append(e.getStateKey())
                    .append(": ").append(e.getStateValue() != null ? e.getStateValue() : "").append('\n');
        }
        return sb.toString();
    }

    static String buildExtractPayload(List<SessionTurn> history, String todoHints) {
        // 取尾部若干轮，避免整段历史过长
        int from = Math.max(0, history.size() - 6);
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(todoHints)) {
            sb.append(todoHints.strip()).append('\n');
        }
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
                String strippedKey = key.strip();
                String strippedValue = value.strip();
                String background = text(node, "background").strip();
                // status 生命周期仅 todo 类：其他 kind 固定 active，模型误产 done/void 不会静默 void 既有 chat 行
                String status = isTodo(kind) ? L2ConflictMerger.normalizeStatus(text(node, "status")) : "active";
                if (isTodo(kind) && !v22TodoGatesPass(strippedKey, strippedValue, background, status)) {
                    continue;
                }
                out.add(new L2ConflictMerger.Candidate(
                        kind, strippedKey, strippedValue, confidence,
                        StringUtils.hasText(background) ? background : null, status));
            }
            return List.copyOf(out);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** v22 P3（仅 todo 强制，其他 kind 不强弃以兼容 chat 现状）：key 场景化 + value 非布尔孤值；background 仅 active 必填（done/void 豁免）。 */
    private static boolean v22TodoGatesPass(String key, String value, String background, String status) {
        boolean backgroundRequired = !"done".equals(status) && !"void".equals(status);
        return (!backgroundRequired || StringUtils.hasText(background))
                && KEY_PATTERN.matcher(key).matches()
                && !isBooleanLoneValue(value);
    }

    private static boolean isTodo(String kind) {
        return "todo".equals(kind);
    }

    static boolean isBooleanLoneValue(String value) {
        return BOOLEAN_LONE_VALUES.contains(value.strip().toLowerCase(Locale.ROOT));
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
