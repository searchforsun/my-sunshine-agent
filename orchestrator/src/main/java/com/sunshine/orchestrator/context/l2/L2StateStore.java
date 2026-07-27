package com.sunshine.orchestrator.context.l2;

import com.sunshine.orchestrator.context.ContextProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * L2 状态读写：TTL 过滤、冲突合并落库、system 块渲染。
 * <p>同 key 可并存 active + superseded（审计）；注入仅 active 且未过期。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class L2StateStore {

    private static final List<String> KIND_ORDER = List.of(
            "profile", "preference", "goal", "agreement", "constraint", "fact", "decision");

    private final UserContextStateRepository repository;
    private final L2ConflictMerger merger;
    private final ContextProperties contextProperties;

    public List<UserContextStateEntity> listInjectable(String userId, String tenantId, Instant now) {
        if (!StringUtils.hasText(userId)) {
            return List.of();
        }
        String tid = tenantId != null ? tenantId : "default";
        Instant clock = now != null ? now : Instant.now();
        List<UserContextStateEntity> active = repository.findByUserIdAndTenantIdAndStatus(userId, tid, "active");
        if (active == null || active.isEmpty()) {
            return List.of();
        }
        List<UserContextStateEntity> out = new ArrayList<>();
        for (UserContextStateEntity e : active) {
            if (isInjectable(e, clock)) {
                out.add(e);
            }
        }
        out.sort(Comparator
                .comparingInt((UserContextStateEntity e) -> kindRank(e.getKind()))
                .thenComparing(e -> e.getStateKey() != null ? e.getStateKey() : ""));
        return List.copyOf(out);
    }

    public String assembleSystemBlock(String userId, String tenantId) {
        List<UserContextStateEntity> entries = listInjectable(userId, tenantId, Instant.now());
        return renderSystemBlock(entries);
    }

    /** 渲染 L2 system 块；无条目返回空串（不写标题）。 */
    public static String renderSystemBlock(List<UserContextStateEntity> entries) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[用户状态 · L2]");
        for (UserContextStateEntity e : entries) {
            if (e == null || !StringUtils.hasText(e.getKind()) || !StringUtils.hasText(e.getStateKey())) {
                continue;
            }
            sb.append('\n').append("- ")
                    .append(e.getKind()).append('/').append(e.getStateKey())
                    .append(": ").append(e.getStateValue() != null ? e.getStateValue() : "");
        }
        return sb.length() > "[用户状态 · L2]".length() ? sb.toString() : "";
    }

    static boolean isInjectable(UserContextStateEntity e, Instant now) {
        if (e == null || !"active".equals(e.getStatus())) {
            return false;
        }
        Instant exp = e.getExpiresAt();
        return exp == null || exp.isAfter(now);
    }

    /**
     * 置信已由调用方过滤；同 key 走 Merger。
     * <p>同 value（strip 后相等）→ 原地刷新 {@code updatedAt}/溯源/置信，不 supersede。
     * <p>ACCEPT 且 value 变化 → 旧条 superseded + 插入新条。
     */
    public void upsert(
            String userId,
            String tenantId,
            L2ConflictMerger.Candidate candidate,
            String sourceMsgId,
            Instant now) {
        if (!StringUtils.hasText(userId) || candidate == null) {
            return;
        }
        if (!StringUtils.hasText(candidate.kind()) || !StringUtils.hasText(candidate.key())) {
            return;
        }
        if (!StringUtils.hasText(candidate.value())) {
            return;
        }
        String tid = tenantId != null ? tenantId : "default";
        String kind = L2ConflictMerger.normalizeKind(candidate.kind());
        String key = candidate.key().strip();
        String value = candidate.value().strip();
        Instant clock = now != null ? now : Instant.now();
        Optional<UserContextStateEntity> existingOpt =
                repository.findByUserIdAndTenantIdAndKindAndStateKeyAndStatus(userId, tid, kind, key, "active");
        UserContextStateEntity existing = existingOpt.orElse(null);
        if (existing != null && sameValue(existing.getStateValue(), value)) {
            refreshSameValue(existing, candidate.confidence(), sourceMsgId, clock);
            return;
        }
        L2ConflictMerger.Decision decision = merger.decide(existing, candidate, contextProperties.getL2());
        if (decision == L2ConflictMerger.Decision.REJECT) {
            log.debug("[ContextL2] reject overwrite user={} kind={} key={} conf={}",
                    userId, kind, key, candidate.confidence());
            return;
        }
        if (existing != null) {
            existing.setStatus("superseded");
            existing.setUpdatedAt(clock);
            repository.save(existing);
        }
        UserContextStateEntity neu = new UserContextStateEntity();
        neu.setId(newId());
        neu.setUserId(userId);
        neu.setTenantId(tid);
        neu.setKind(kind);
        neu.setStateKey(key);
        neu.setStateValue(value);
        neu.setConfidence(candidate.confidence());
        neu.setStatus("active");
        neu.setExpiresAt(expiresAtFor(kind, clock));
        neu.setSourceMsgId(sourceMsgId);
        neu.setCreatedAt(clock);
        neu.setUpdatedAt(clock);
        repository.save(neu);
    }

    /** 同 key+value：只刷新时间（及溯源/更高置信），不产生 superseded。 */
    private void refreshSameValue(
            UserContextStateEntity existing,
            double incomingConfidence,
            String sourceMsgId,
            Instant clock) {
        existing.setUpdatedAt(clock);
        if (incomingConfidence > existing.getConfidence()) {
            existing.setConfidence(incomingConfidence);
        }
        if (StringUtils.hasText(sourceMsgId)) {
            existing.setSourceMsgId(sourceMsgId);
        }
        repository.save(existing);
        log.debug("[ContextL2] refresh same value id={} key={}", existing.getId(), existing.getStateKey());
    }

    static boolean sameValue(String a, String b) {
        if (!StringUtils.hasText(a) || !StringUtils.hasText(b)) {
            return false;
        }
        return a.strip().equals(b.strip());
    }

    Instant expiresAtFor(String kind, Instant now) {
        ContextProperties.L2 l2 = contextProperties.getL2();
        int days = ttlDays(kind, l2);
        if (days <= 0) {
            return null;
        }
        return now.plus(days, ChronoUnit.DAYS);
    }

    static int ttlDays(String kind, ContextProperties.L2 l2) {
        if (l2 == null) {
            l2 = new ContextProperties.L2();
        }
        return switch (L2ConflictMerger.normalizeKind(kind)) {
            case "preference", "profile" -> l2.getPreferenceTtlDays();
            case "agreement" -> l2.getAgreementTtlDays();
            case "goal" -> l2.getGoalTtlDays();
            case "decision" -> l2.getDecisionTtlDays();
            case "fact" -> l2.getFactTtlDays();
            case "constraint" -> l2.getConstraintTtlDays();
            case "reasoning" -> l2.getReasoningTtlDays();
            case "option" -> l2.getOptionTtlDays();
            case "interim_conclusion" -> l2.getInterimConclusionTtlDays();
            case "topic" -> l2.getTopicTtlDays();
            default -> l2.getFactTtlDays();
        };
    }

    private static int kindRank(String kind) {
        String k = kind != null ? kind.toLowerCase(Locale.ROOT) : "";
        int idx = KIND_ORDER.indexOf(k);
        return idx >= 0 ? idx : KIND_ORDER.size();
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
