package com.sunshine.orchestrator.context.l2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.client.LlmGatewayClient;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * L2 语义冲突识别（§6.4 写路径）：字面快路径未命中时，对同 kind 既有 active 条目做
 * LLM 语义判定（Catalog {@code context.l2.merge}），从源头防「语义相似 key 各自成条 / value 相反矛盾」。
 * <p>任何失败（Catalog 缺失 / LLM 异常 / 解析失败 / targetId 非法）→ NOOP：
 * 走正常新增，保守回退现行为；历史遗留矛盾由批量审计（{@code context.l2.audit}）兜底。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class L2SemanticMergeService {

    public static final String MERGE_PROMPT = "context.l2.merge";

    private static final ObjectMapper OM = new ObjectMapper();

    private static final Set<String> VALID_ACTIONS = Set.of("NOOP", "MERGE", "UPDATE", "CONFLICT");

    private final LlmGatewayClient llmGatewayClient;
    private final PromptCatalogHolder catalogHolder;

    public enum Action {
        /** 语义无关 → 正常新增 */
        NOOP,
        /** 语义等价/同指 → 归一到 target（刷新值 + 置信取高，不产生 superseded） */
        MERGE,
        /** 语义更新（时间/场景演进）→ target superseded + 新增候选 */
        UPDATE,
        /** 语义相反且同为当前陈述 → target 与候选双标 conflict（不注入） */
        CONFLICT
    }

    /** 语义判定结果；targetId 必须来自候选集（解析时校验，禁止编造）。 */
    public record Verdict(
            Action action, String targetId,
            String mergedKey, String mergedValue, String mergedBackground, String reason) {
        static Verdict noop(String reason) {
            return new Verdict(Action.NOOP, null, null, null, null, reason);
        }
    }

    /** 语义判定；失败一律回退 NOOP（不阻断写路径）。 */
    public Verdict judge(L2ConflictMerger.Candidate candidate, List<UserContextStateEntity> sameKindActives) {
        if (candidate == null || sameKindActives == null || sameKindActives.isEmpty()) {
            return Verdict.noop("无候选");
        }
        String system = catalogHolder.requireText(MERGE_PROMPT);
        if (!StringUtils.hasText(system)) {
            return Verdict.noop("missing catalog");
        }
        String raw;
        try {
            raw = llmGatewayClient.complete(system, buildPayload(candidate, sameKindActives));
        } catch (Exception e) {
            log.warn("[ContextL2] semantic merge LLM 失败: {}", e.getMessage());
            return Verdict.noop("llm error");
        }
        return parseVerdict(raw, sameKindActives);
    }

    static String buildPayload(L2ConflictMerger.Candidate candidate, List<UserContextStateEntity> sameKindActives) {
        StringBuilder sb = new StringBuilder();
        sb.append("【新候选】\n")
                .append("kind: ").append(candidate.kind()).append('\n')
                .append("key: ").append(candidate.key()).append('\n')
                .append("value: ").append(candidate.value()).append('\n');
        if (StringUtils.hasText(candidate.background())) {
            sb.append("background: ").append(candidate.background()).append('\n');
        }
        sb.append("【同 kind 既有条目】\n");
        for (UserContextStateEntity e : sameKindActives) {
            if (e == null || !StringUtils.hasText(e.getId())) {
                continue;
            }
            sb.append("- id: ").append(e.getId())
                    .append(" | key: ").append(e.getStateKey())
                    .append(" | value: ").append(e.getStateValue() != null ? e.getStateValue() : "");
            if (StringUtils.hasText(e.getBackground())) {
                sb.append(" | background: ").append(e.getBackground());
            }
            sb.append('\n');
        }
        return sb.toString().strip();
    }

    static Verdict parseVerdict(String raw, List<UserContextStateEntity> sameKindActives) {
        if (!StringUtils.hasText(raw)) {
            return Verdict.noop("empty response");
        }
        try {
            JsonNode root = OM.readTree(extractJsonObject(raw));
            String actionText = text(root, "action").toUpperCase(Locale.ROOT);
            if (!VALID_ACTIONS.contains(actionText) || "NOOP".equals(actionText)) {
                return Verdict.noop(text(root, "reason"));
            }
            String targetId = text(root, "targetId");
            if (!candidateIdExists(sameKindActives, targetId)) {
                return Verdict.noop("invalid targetId");
            }
            return new Verdict(
                    Action.valueOf(actionText), targetId,
                    emptyToNull(text(root, "mergedKey")),
                    emptyToNull(text(root, "mergedValue")),
                    emptyToNull(text(root, "mergedBackground")),
                    text(root, "reason"));
        } catch (Exception e) {
            return Verdict.noop("parse error");
        }
    }

    private static boolean candidateIdExists(List<UserContextStateEntity> rows, String targetId) {
        if (!StringUtils.hasText(targetId)) {
            return false;
        }
        String id = targetId.strip();
        for (UserContextStateEntity e : rows) {
            if (e != null && id.equals(e.getId())) {
                return true;
            }
        }
        return false;
    }

    static String extractJsonObject(String raw) {
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
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    private static String emptyToNull(String s) {
        return StringUtils.hasText(s) ? s.strip() : null;
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
