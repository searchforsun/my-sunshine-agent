package com.sunshine.orchestrator.context.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.client.LlmGatewayClient;
import com.sunshine.orchestrator.context.ContextProperties;
import com.sunshine.orchestrator.context.l1.ConversationContextL1Entity;
import com.sunshine.orchestrator.context.l1.ConversationContextL1Repository;
import com.sunshine.orchestrator.context.l1.ConversationContextL1Store;
import com.sunshine.orchestrator.context.l2.UserContextStateEntity;
import com.sunshine.orchestrator.context.l2.UserContextStateRepository;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * L2/L1 腐败与矛盾审计：规则快扫 + Catalog LLM；明确 void、暧昧 conflict；失败仅日志。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextAuditService {

    public static final String L2_AUDIT_PROMPT = "context.l2.audit";
    public static final String L1_AUDIT_PROMPT = "context.l1.audit";

    private static final ObjectMapper OM = new ObjectMapper();
    private static final int L1_PAYLOAD_BUDGET = 4000;
    private static final int MIN_VALUE_LEN = 1;

    private final ContextProperties contextProperties;
    private final UserContextStateRepository l2Repository;
    private final ConversationContextL1Repository l1Repository;
    private final ConversationContextL1Store l1Store;
    private final LlmGatewayClient llmGatewayClient;
    private final PromptCatalogHolder catalogHolder;

    /** user|tenant → last audit epoch ms（抽取后防抖） */
    private final ConcurrentHashMap<String, Long> extractAuditAt = new ConcurrentHashMap<>();

    public record AuditStats(int voided, int conflicted, int l1Patched) {
    }

    @Async
    public void auditUserLightAsync(String userId, String tenantId) {
        try {
            auditUserLight(userId, tenantId, true);
        } catch (Exception e) {
            log.warn("[ContextAudit] async failed user={}: {}", userId, e.getMessage());
        }
    }

    /**
     * 单用户轻量审计。{@code debounce} 为 true 时尊重抽取防抖窗口。
     */
    public AuditStats auditUserLight(String userId, String tenantId, boolean debounce) {
        if (!contextProperties.isEnabled()
                || !contextProperties.getMaintenance().isAuditEnabled()
                || !StringUtils.hasText(userId)) {
            return new AuditStats(0, 0, 0);
        }
        String tid = tenantId != null ? tenantId : "default";
        if (debounce && !tryAcquireExtractDebounce(userId, tid)) {
            return new AuditStats(0, 0, 0);
        }
        Instant now = Instant.now();
        int voided = ruleDedupeActiveSameKey(userId, tid, now);
        voided += ruleVoidJunk(userId, tid, now);
        List<UserContextStateEntity> active =
                l2Repository.findByUserIdAndTenantIdAndStatus(userId, tid, "active");
        if (active == null) {
            active = List.of();
        }
        L2AuditDecision l2Decision = runL2LlmAudit(active);
        Set<String> activeIds = new HashSet<>();
        for (UserContextStateEntity e : active) {
            if (e != null && StringUtils.hasText(e.getId())) {
                activeIds.add(e.getId());
            }
        }
        int conflicted = 0;
        for (String id : l2Decision.voidIds()) {
            if (!activeIds.contains(id)) {
                continue;
            }
            if (applyStatus(id, "void", now)) {
                voided++;
                activeIds.remove(id);
            }
        }
        for (String id : l2Decision.conflictIds()) {
            if (!activeIds.contains(id) || l2Decision.voidIds().contains(id)) {
                continue;
            }
            if (applyStatus(id, "conflict", now)) {
                conflicted++;
                activeIds.remove(id);
            }
        }
        List<ConversationContextL1Entity> l1Rows = l1Repository.findByUserIdAndTenantId(userId, tid);
        int l1Patched = 0;
        if (l1Rows != null && !l1Rows.isEmpty()) {
            List<UserContextStateEntity> stillActive =
                    l2Repository.findByUserIdAndTenantIdAndStatus(userId, tid, "active");
            L1AuditDecision l1Decision = runL1LlmAudit(stillActive != null ? stillActive : List.of(), l1Rows);
            l1Patched = applyL1Patches(l1Rows, l1Decision, now);
        }
        if (voided > 0 || conflicted > 0 || l1Patched > 0) {
            log.info("[ContextAudit] user={} tenant={} voided={} conflicted={} l1Patched={}",
                    userId, tid, voided, conflicted, l1Patched);
        }
        return new AuditStats(voided, conflicted, l1Patched);
    }

    /** 小时维护：按近期 active L2 用户批量轻量审计（无抽取防抖）。 */
    public AuditStats auditRecentUsers() {
        ContextProperties.Maintenance m = contextProperties.getMaintenance();
        if (!contextProperties.isEnabled() || !m.isAuditEnabled()) {
            return new AuditStats(0, 0, 0);
        }
        int maxUsers = Math.max(1, m.getAuditMaxUsersPerTick());
        List<UserContextStateEntity> active = l2Repository.findByStatus("active");
        if (active == null || active.isEmpty()) {
            return new AuditStats(0, 0, 0);
        }
        Map<String, Instant> latest = new HashMap<>();
        Map<String, String[]> keys = new HashMap<>();
        for (UserContextStateEntity e : active) {
            if (e == null || !StringUtils.hasText(e.getUserId())) {
                continue;
            }
            String tid = e.getTenantId() != null ? e.getTenantId() : "default";
            String uk = e.getUserId() + "|" + tid;
            Instant u = e.getUpdatedAt() != null ? e.getUpdatedAt() : Instant.EPOCH;
            Instant prev = latest.get(uk);
            if (prev == null || u.isAfter(prev)) {
                latest.put(uk, u);
                keys.put(uk, new String[]{e.getUserId(), tid});
            }
        }
        List<String> ordered = new ArrayList<>(latest.keySet());
        ordered.sort(Comparator.comparing((String uk) -> latest.get(uk)).reversed());
        int voided = 0;
        int conflicted = 0;
        int l1Patched = 0;
        int n = 0;
        for (String uk : ordered) {
            if (n >= maxUsers) {
                break;
            }
            String[] pair = keys.get(uk);
            if (pair == null) {
                continue;
            }
            AuditStats s = auditUserLight(pair[0], pair[1], false);
            voided += s.voided();
            conflicted += s.conflicted();
            l1Patched += s.l1Patched();
            n++;
        }
        return new AuditStats(voided, conflicted, l1Patched);
    }

    /**
     * 同 (kind, state_key) 多条 active：保留置信最高（平手取 updated_at 新），其余 void。
     */
    int ruleDedupeActiveSameKey(String userId, String tenantId, Instant now) {
        List<UserContextStateEntity> active =
                l2Repository.findByUserIdAndTenantIdAndStatus(userId, tenantId, "active");
        if (active == null || active.size() < 2) {
            return 0;
        }
        Map<String, List<UserContextStateEntity>> groups = new LinkedHashMap<>();
        for (UserContextStateEntity e : active) {
            if (e == null || !StringUtils.hasText(e.getKind()) || !StringUtils.hasText(e.getStateKey())) {
                continue;
            }
            String gk = e.getKind().toLowerCase(Locale.ROOT) + "|" + e.getStateKey().strip();
            groups.computeIfAbsent(gk, k -> new ArrayList<>()).add(e);
        }
        Instant clock = now != null ? now : Instant.now();
        int voided = 0;
        for (List<UserContextStateEntity> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            group.sort(Comparator
                    .comparingDouble(UserContextStateEntity::getConfidence).reversed()
                    .thenComparing(e -> e.getUpdatedAt() != null ? e.getUpdatedAt() : Instant.EPOCH,
                            Comparator.reverseOrder()));
            for (int i = 1; i < group.size(); i++) {
                UserContextStateEntity loser = group.get(i);
                loser.setStatus("void");
                loser.setUpdatedAt(clock);
                l2Repository.save(loser);
                voided++;
            }
        }
        return voided;
    }

    int ruleVoidJunk(String userId, String tenantId, Instant now) {
        List<UserContextStateEntity> active =
                l2Repository.findByUserIdAndTenantIdAndStatus(userId, tenantId, "active");
        if (active == null || active.isEmpty()) {
            return 0;
        }
        Instant clock = now != null ? now : Instant.now();
        int voided = 0;
        for (UserContextStateEntity e : active) {
            if (e == null) {
                continue;
            }
            String v = e.getStateValue();
            if (v != null && v.strip().length() >= MIN_VALUE_LEN) {
                continue;
            }
            e.setStatus("void");
            e.setUpdatedAt(clock);
            l2Repository.save(e);
            voided++;
        }
        return voided;
    }

    private boolean tryAcquireExtractDebounce(String userId, String tenantId) {
        long debounce = contextProperties.getMaintenance().getAuditExtractDebounceMs();
        if (debounce <= 0) {
            return true;
        }
        String key = userId + "|" + tenantId;
        long now = System.currentTimeMillis();
        Long prev = extractAuditAt.get(key);
        if (prev != null && now - prev < debounce) {
            return false;
        }
        extractAuditAt.put(key, now);
        return true;
    }

    private boolean applyStatus(String id, String status, Instant now) {
        return l2Repository.findById(id).map(e -> {
            e.setStatus(status);
            e.setUpdatedAt(now);
            l2Repository.save(e);
            return true;
        }).orElse(false);
    }

    private L2AuditDecision runL2LlmAudit(List<UserContextStateEntity> active) {
        if (active == null || active.isEmpty()) {
            return L2AuditDecision.empty();
        }
        String system = catalogHolder.requireText(L2_AUDIT_PROMPT);
        if (!StringUtils.hasText(system)) {
            log.warn("[ContextAudit] missing catalog {}", L2_AUDIT_PROMPT);
            return L2AuditDecision.empty();
        }
        String payload = buildL2Payload(active);
        try {
            String raw = llmGatewayClient.complete(system, payload);
            return parseL2Decision(raw);
        } catch (Exception e) {
            log.warn("[ContextAudit] L2 LLM failed: {}", e.getMessage());
            return L2AuditDecision.empty();
        }
    }

    private L1AuditDecision runL1LlmAudit(
            List<UserContextStateEntity> active, List<ConversationContextL1Entity> l1Rows) {
        String system = catalogHolder.requireText(L1_AUDIT_PROMPT);
        if (!StringUtils.hasText(system)) {
            log.warn("[ContextAudit] missing catalog {}", L1_AUDIT_PROMPT);
            return L1AuditDecision.empty();
        }
        String payload = buildL1Payload(active, l1Rows);
        if (!StringUtils.hasText(payload)) {
            return L1AuditDecision.empty();
        }
        try {
            String raw = llmGatewayClient.complete(system, payload);
            return parseL1Decision(raw);
        } catch (Exception e) {
            log.warn("[ContextAudit] L1 LLM failed: {}", e.getMessage());
            return L1AuditDecision.empty();
        }
    }

    private int applyL1Patches(
            List<ConversationContextL1Entity> rows, L1AuditDecision decision, Instant now) {
        if (decision == null || rows == null || rows.isEmpty()) {
            return 0;
        }
        Map<String, ConversationContextL1Entity> byConv = new HashMap<>();
        for (ConversationContextL1Entity row : rows) {
            if (row != null && StringUtils.hasText(row.getConvId())) {
                byConv.put(row.getConvId(), row);
            }
        }
        int patched = 0;
        Set<String> touched = new HashSet<>();
        for (Map.Entry<String, List<String>> e : decision.removeMidKeys().entrySet()) {
            ConversationContextL1Entity row = byConv.get(e.getKey());
            if (row == null || e.getValue() == null || e.getValue().isEmpty()) {
                continue;
            }
            Map<String, String> mid = new LinkedHashMap<>(l1Store.parseMidAnswers(row));
            boolean changed = false;
            for (String msgId : e.getValue()) {
                if (StringUtils.hasText(msgId) && mid.containsKey(msgId)) {
                    mid.remove(msgId);
                    changed = true;
                }
            }
            if (changed) {
                row.setMidAnswers(writeMidJson(mid));
                touched.add(row.getConvId());
            }
        }
        for (Map.Entry<String, String> e : decision.farSummaryByConv().entrySet()) {
            ConversationContextL1Entity row = byConv.get(e.getKey());
            if (row == null || e.getValue() == null) {
                continue;
            }
            String next = e.getValue();
            String prev = row.getFarSummary() != null ? row.getFarSummary() : "";
            if (!prev.equals(next)) {
                row.setFarSummary(next);
                touched.add(row.getConvId());
            }
        }
        for (String convId : touched) {
            ConversationContextL1Entity row = byConv.get(convId);
            if (row == null) {
                continue;
            }
            row.setUpdatedAt(now);
            l1Repository.save(row);
            patched++;
        }
        return patched;
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
                    .append(" confidence=").append(e.getConfidence())
                    .append('\n');
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

    static L2AuditDecision parseL2Decision(String raw) {
        JsonNode root = parseObject(raw);
        if (root == null) {
            return L2AuditDecision.empty();
        }
        Set<String> voidIds = readIdSet(root.get("voidIds"));
        Set<String> conflictIds = readIdSet(root.get("conflictIds"));
        return new L2AuditDecision(voidIds, conflictIds);
    }

    static L1AuditDecision parseL1Decision(String raw) {
        JsonNode root = parseObject(raw);
        if (root == null) {
            return L1AuditDecision.empty();
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
        return new L1AuditDecision(Map.copyOf(removeMid), Map.copyOf(far));
    }

    record L2AuditDecision(Set<String> voidIds, Set<String> conflictIds) {
        static L2AuditDecision empty() {
            return new L2AuditDecision(Set.of(), Set.of());
        }
    }

    record L1AuditDecision(Map<String, List<String>> removeMidKeys, Map<String, String> farSummaryByConv) {
        static L1AuditDecision empty() {
            return new L1AuditDecision(Map.of(), Map.of());
        }
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

    private static String writeMidJson(Map<String, String> mid) {
        try {
            return OM.writeValueAsString(new LinkedHashMap<>(mid != null ? mid : Map.of()));
        } catch (Exception e) {
            throw new IllegalStateException("mid_answers serialize failed", e);
        }
    }
}
