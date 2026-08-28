package com.sunshine.orchestrator.context.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.client.LlmGatewayClient;
import com.sunshine.orchestrator.context.l1.ConversationContextL1Entity;
import com.sunshine.orchestrator.context.l2.UserContextStateEntity;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Catalog LLM 审阅：拼 payload、调 Gateway、解析 JSON；失败返回 empty。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContextLlmAuditClient {

    public static final String L2_AUDIT_PROMPT = "context.l2.audit";
    public static final String L1_AUDIT_PROMPT = "context.l1.audit";

    private static final ObjectMapper OM = new ObjectMapper();
    private static final int L1_PAYLOAD_BUDGET = 4000;

    private final LlmGatewayClient llmGatewayClient;
    private final PromptCatalogHolder catalogHolder;

    ContextAuditDecisions.L2AuditDecision auditL2(List<UserContextStateEntity> active) {
        if (active == null || active.isEmpty()) {
            return ContextAuditDecisions.L2AuditDecision.empty();
        }
        String system = catalogHolder.requireText(L2_AUDIT_PROMPT);
        if (!StringUtils.hasText(system)) {
            log.warn("[ContextAudit] missing catalog {}", L2_AUDIT_PROMPT);
            return ContextAuditDecisions.L2AuditDecision.empty();
        }
        String payload = buildL2Payload(active);
        try {
            String raw = llmGatewayClient.complete(system, payload);
            return parseL2Decision(raw);
        } catch (Exception e) {
            log.warn("[ContextAudit] L2 LLM failed: {}", e.getMessage());
            return ContextAuditDecisions.L2AuditDecision.empty();
        }
    }

    ContextAuditDecisions.L1AuditDecision auditL1(
            List<UserContextStateEntity> active, List<ConversationContextL1Entity> l1Rows) {
        String system = catalogHolder.requireText(L1_AUDIT_PROMPT);
        if (!StringUtils.hasText(system)) {
            log.warn("[ContextAudit] missing catalog {}", L1_AUDIT_PROMPT);
            return ContextAuditDecisions.L1AuditDecision.empty();
        }
        String payload = buildL1Payload(active, l1Rows);
        if (!StringUtils.hasText(payload)) {
            return ContextAuditDecisions.L1AuditDecision.empty();
        }
        try {
            String raw = llmGatewayClient.complete(system, payload);
            return parseL1Decision(raw);
        } catch (Exception e) {
            log.warn("[ContextAudit] L1 LLM failed: {}", e.getMessage());
            return ContextAuditDecisions.L1AuditDecision.empty();
        }
    }

    static String buildL2Payload(List<UserContextStateEntity> active) {
        StringBuilder sb = new StringBuilder();
        sb.append("【L2 active 条目】\n");
        for (UserContextStateEntity e : active) {
            if (e == null) {
                continue;
            }
            sb.append("id=").append(e.getId())
                    .append(" kind=").append(e.getKind())
                    .append(" key=").append(e.getStateKey())
                    .append(" value=").append(e.getStateValue())
                    .append(" confidence=").append(e.getConfidence());
            if (StringUtils.hasText(e.getBackground())) {
                sb.append(" background=").append(e.getBackground());
            }
            sb.append('\n');
        }
        return sb.toString().strip();
    }

    static String buildL1Payload(
            List<UserContextStateEntity> active, List<ConversationContextL1Entity> l1Rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("【对照 L2】\n");
        if (active != null) {
            for (UserContextStateEntity e : active) {
                if (e == null) {
                    continue;
                }
                sb.append("- ").append(e.getKind()).append('/').append(e.getStateKey())
                        .append(": ").append(e.getStateValue()).append('\n');
            }
        }
        sb.append("【L1 派生】\n");
        int budget = L1_PAYLOAD_BUDGET;
        for (ConversationContextL1Entity row : l1Rows) {
            if (row == null || !StringUtils.hasText(row.getConvId())) {
                continue;
            }
            String block = "convId=" + row.getConvId()
                    + "\nfar_summary=" + truncate(row.getFarSummary(), 800)
                    + "\nmid_answers=" + truncate(row.getMidAnswers(), 1200)
                    + "\n";
            if (block.length() > budget) {
                break;
            }
            sb.append(block);
            budget -= block.length();
        }
        return sb.toString().strip();
    }

    static ContextAuditDecisions.L2AuditDecision parseL2Decision(String raw) {
        JsonNode root = parseObject(raw);
        if (root == null) {
            return ContextAuditDecisions.L2AuditDecision.empty();
        }
        Set<String> voidIds = readIdSet(root.get("voidIds"));
        Set<String> conflictIds = readIdSet(root.get("conflictIds"));
        return new ContextAuditDecisions.L2AuditDecision(voidIds, conflictIds);
    }

    static ContextAuditDecisions.L1AuditDecision parseL1Decision(String raw) {
        JsonNode root = parseObject(raw);
        if (root == null) {
            return ContextAuditDecisions.L1AuditDecision.empty();
        }
        Map<String, List<String>> removeMid = new LinkedHashMap<>();
        JsonNode midNode = root.get("removeMidKeys");
        if (midNode != null && midNode.isObject()) {
            midNode.fields().forEachRemaining(en -> {
                List<String> ids = new ArrayList<>();
                if (en.getValue() != null && en.getValue().isArray()) {
                    for (JsonNode n : en.getValue()) {
                        if (n != null && n.isTextual() && StringUtils.hasText(n.asText())) {
                            ids.add(n.asText().strip());
                        }
                    }
                }
                if (!ids.isEmpty() && StringUtils.hasText(en.getKey())) {
                    removeMid.put(en.getKey(), List.copyOf(ids));
                }
            });
        }
        Map<String, String> far = new LinkedHashMap<>();
        JsonNode farNode = root.get("farSummaryByConv");
        if (farNode != null && farNode.isObject()) {
            farNode.fields().forEachRemaining(en -> {
                if (StringUtils.hasText(en.getKey()) && en.getValue() != null && en.getValue().isTextual()) {
                    far.put(en.getKey(), en.getValue().asText());
                }
            });
        }
        return new ContextAuditDecisions.L1AuditDecision(Map.copyOf(removeMid), Map.copyOf(far));
    }

    private static Set<String> readIdSet(JsonNode node) {
        if (node == null || !node.isArray()) {
            return Set.of();
        }
        Set<String> out = new HashSet<>();
        for (JsonNode n : node) {
            if (n != null && n.isTextual() && StringUtils.hasText(n.asText())) {
                out.add(n.asText().strip());
            }
        }
        return Set.copyOf(out);
    }

    private static JsonNode parseObject(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            String json = extractJsonObject(raw);
            JsonNode root = OM.readTree(json);
            return root != null && root.isObject() ? root : null;
        } catch (Exception e) {
            return null;
        }
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

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
    }
}
