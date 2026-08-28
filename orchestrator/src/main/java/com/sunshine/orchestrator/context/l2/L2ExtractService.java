package com.sunshine.orchestrator.context.l2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.client.LlmGatewayClient;
import com.sunshine.orchestrator.context.ContextProperties;
import com.sunshine.orchestrator.context.ContextWritePolicy;
import com.sunshine.orchestrator.context.SessionTurn;
import com.sunshine.orchestrator.context.audit.ContextAuditService;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 每轮 completed 后静默抽取 L2 状态：LLM + Catalog {@code context.memory.extract}（scope 参数化）→ 置信门禁 → Merger upsert。
 * todo 类叠加 v22 门禁（key 场景化 / background 必填 / value 非布尔孤值）；其他 kind 不强弃，兼容 chat 现状。
 * <p>O3：置信门禁与 v22 门禁的判定标准收敛至 {@link ContextWritePolicy}（写路由策略单点），本类仅执行。
 * 成功后触发轻量腐败/矛盾审计（异步、可防抖）。失败仅日志，不阻断用户路径。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class L2ExtractService {

    public static final String EXTRACT_PROMPT = "context.memory.extract";

    private static final Set<String> VALID_KINDS = Set.of(
            "profile", "preference", "goal", "agreement", "constraint", "fact", "decision",
            "process_note", "todo");

    private static final ObjectMapper OM = new ObjectMapper();

    private final ContextProperties contextProperties;
    private final LlmGatewayClient llmGatewayClient;
    private final PromptCatalogHolder catalogHolder;
    private final L2StateStore l2StateStore;
    private final ContextAuditService contextAuditService;

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
        extract(userId, tenantId, sourceMsgId, history, msgAt, null);
    }

    /** 带业务场景作用域抽取（authority §5.5 ④）：scene 非空时偏好落 {@code biz_scene_scope}。 */
    public void extract(
            String userId,
            String tenantId,
            String sourceMsgId,
            List<SessionTurn> history,
            Instant msgAt,
            String bizSceneScope) {
        if (!StringUtils.hasText(userId)) {
            return;
        }
        runExtract("user", userId, null, tenantId, sourceMsgId, history, msgAt, bizSceneScope);
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
        runExtract("workspace", null, workspaceId, tenantId, sourceMsgId, history, msgAt, null);
    }

    private void runExtract(
            String scope,
            String userId,
            String workspaceId,
            String tenantId,
            String sourceMsgId,
            List<SessionTurn> history,
            Instant msgAt,
            String bizSceneScope) {
        if (!contextProperties.isEnabled() || history == null || history.isEmpty()) {
            return;
        }
        String system = buildSystemPrompt(
                catalogHolder.requireText(EXTRACT_PROMPT), scope, bizSceneScope);
        if (!StringUtils.hasText(system)) {
            log.warn("[ContextL2] missing catalog {}", EXTRACT_PROMPT);
            return;
        }
        String userPayload = buildExtractPayload(history, existingStateHints(userId, workspaceId, tenantId));
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
            // O3：kind 级置信门禁由写路由策略单点定义
            double minConf = ContextWritePolicy.l2MinConfidenceFor(c.kind(), l2);
            if (c.confidence() < minConf) {
                log.debug("[ContextL2] drop low confidence kind={} key={} conf={}",
                        c.kind(), c.key(), c.confidence());
                continue;
            }
            if (workspaceId != null) {
                l2StateStore.upsertWorkspace(workspaceId, tenantId, c, sourceMsgId, now);
            } else {
                l2StateStore.upsert(userId, tenantId, c, sourceMsgId, now, bizSceneScope);
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

    /** Catalog 正文 {scope}、{biz_scene_scope}、{today} 占位替换；缺省业务场景作用域归一为 *（全局）。 */
    static String buildSystemPrompt(String catalogText, String scope, String bizSceneScope) {
        return buildSystemPrompt(catalogText, scope, bizSceneScope, LocalDate.now());
    }

    /** 测试入口：固定 today，避免相对日期换算随执行日期漂移。 */
    static String buildSystemPrompt(
            String catalogText, String scope, String bizSceneScope, LocalDate today) {
        if (!StringUtils.hasText(catalogText)) {
            return "";
        }
        String biz = StringUtils.hasText(bizSceneScope) ? bizSceneScope.strip() : "*";
        String todayIso = today != null ? today.toString() : LocalDate.now().toString();
        return catalogText
                .replace("{scope}", scope)
                .replace("{biz_scene_scope}", biz)
                .replace("{today}", todayIso);
    }

    /**
     * 既有 active 状态参照（scope 维度）：让 LLM 沿用原 key/kind/措辞，避免每轮从零起 key 产生跨 kind 重复与同义措辞漂移。
     * todo 类：完成/取消时输出 status=void 且 key 必须与下列一致；
     * 其他 kind：已存在的条目若未变化不要重复输出；变化时沿用原 key/kind 产出 status=active（走字面快路径刷新）。
     */
    private String existingStateHints(String userId, String workspaceId, String tenantId) {
        List<UserContextStateEntity> active = workspaceId != null
                ? l2StateStore.listInjectableWorkspace(workspaceId, tenantId, Instant.now())
                : l2StateStore.listInjectable(userId, tenantId, Instant.now());
        if (active == null || active.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【既有用户状态（仅作参照；未变化的既有条目不要重复输出，变化时沿用原 key/kind/措辞）】\n");
        for (UserContextStateEntity e : active) {
            if (e == null || !StringUtils.hasText(e.getKind()) || !StringUtils.hasText(e.getStateKey())) {
                continue;
            }
            sb.append("- kind=").append(e.getKind())
                    .append(" key=").append(e.getStateKey())
                    .append(" value=").append(e.getStateValue() != null ? e.getStateValue() : "");
            if ("todo".equals(e.getKind())) {
                sb.append("（完成/取消时输出此 key 且 status=void）");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    static String buildExtractPayload(List<SessionTurn> history, String stateHints) {
        // 取尾部若干轮，避免整段历史过长
        int from = Math.max(0, history.size() - 6);
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(stateHints)) {
            sb.append(stateHints.strip()).append('\n');
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
                if (isTodo(kind) && !ContextWritePolicy.l2TodoGatePasses(
                        strippedKey, strippedValue, background, status)) {
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

    private static boolean isTodo(String kind) {
        return "todo".equals(kind);
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
