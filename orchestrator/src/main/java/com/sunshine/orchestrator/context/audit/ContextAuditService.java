package com.sunshine.orchestrator.context.audit;

import com.sunshine.orchestrator.context.ContextProperties;
import com.sunshine.orchestrator.context.l1.ConversationContextL1Entity;
import com.sunshine.orchestrator.context.l1.ConversationContextL1Repository;
import com.sunshine.orchestrator.context.l2.UserContextStateEntity;
import com.sunshine.orchestrator.context.l2.UserContextStateRepository;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * L2/L1 腐败与矛盾审计编排：规则快扫 + Catalog LLM；明确 void、暧昧 conflict；失败仅日志。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextAuditService {

    public static final String L2_AUDIT_PROMPT = ContextLlmAuditClient.L2_AUDIT_PROMPT;
    public static final String L1_AUDIT_PROMPT = ContextLlmAuditClient.L1_AUDIT_PROMPT;

    private final ContextProperties contextProperties;
    private final UserContextStateRepository l2Repository;
    private final ConversationContextL1Repository l1Repository;
    private final L2RuleAuditor l2RuleAuditor;
    private final ContextLlmAuditClient llmAuditClient;
    private final L1AuditApplier l1AuditApplier;

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
        int voided = l2RuleAuditor.dedupeActiveSameKey(userId, tid, now);
        voided += l2RuleAuditor.voidJunk(userId, tid, now);
        List<UserContextStateEntity> active =
                l2Repository.findByUserIdAndTenantIdAndStatus(userId, tid, "active");
        if (active == null) {
            active = List.of();
        }
        ContextAuditDecisions.L2AuditDecision l2Decision = llmAuditClient.auditL2(active);
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
            ContextAuditDecisions.L1AuditDecision l1Decision =
                    llmAuditClient.auditL1(stillActive != null ? stillActive : List.of(), l1Rows);
            l1Patched = l1AuditApplier.apply(l1Rows, l1Decision, now);
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
}
