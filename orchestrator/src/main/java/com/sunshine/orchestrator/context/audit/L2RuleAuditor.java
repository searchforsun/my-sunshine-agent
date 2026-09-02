package com.sunshine.orchestrator.context.audit;

import com.sunshine.orchestrator.context.l2.ContextKind;
import com.sunshine.orchestrator.context.l2.UserContextStateEntity;
import com.sunshine.orchestrator.context.l2.UserContextStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * L2 规则快扫：同 key 去重、空值 junk void（无 LLM）。
 */
@Component
@RequiredArgsConstructor
public class L2RuleAuditor {

    private static final int MIN_VALUE_LEN = 1;

    private final UserContextStateRepository l2Repository;

    /**
     * 同 (kind, state_key) 多条 active：保留置信最高（平手取 updated_at 新），其余 void。
     * 同 value 跨 kind 重复（如 fact/travel.origin 与 profile/location.current_city 同值）：
     * 保留置信最高且 kind 语义更具体的（profile > fact > preference），其余 void。
     */
    public int dedupeActiveSameKey(String userId, String tenantId, Instant now) {
        List<UserContextStateEntity> active =
                l2Repository.findByUserIdAndTenantIdAndStatus(userId, tenantId, "active");
        if (active == null || active.size() < 2) {
            return 0;
        }
        Instant clock = now != null ? now : Instant.now();
        int voided = 0;

        // ① 同 kind+key 去重
        Map<String, List<UserContextStateEntity>> groups = new LinkedHashMap<>();
        for (UserContextStateEntity e : active) {
            if (e == null || !StringUtils.hasText(e.getKind()) || !StringUtils.hasText(e.getStateKey())) {
                continue;
            }
            String gk = e.getKind().toLowerCase(Locale.ROOT) + "|" + e.getStateKey().strip();
            groups.computeIfAbsent(gk, k -> new ArrayList<>()).add(e);
        }
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

        // ② 跨 kind 同 value 去重（同义事实落不同 kind）
        voided += dedupeSameValue(active, clock);
        return voided;
    }

    /**
     * 跨 kind 同 value 去重：同 value（strip 后相等）多条 active，
     * 保留置信最高且 kind 语义更具体的（profile 优先于 fact，fact 优先于 preference），
     * 其余 void。
     */
    private int dedupeSameValue(List<UserContextStateEntity> active, Instant clock) {
        Map<String, List<UserContextStateEntity>> byValue = new LinkedHashMap<>();
        for (UserContextStateEntity e : active) {
            if (e == null || !"active".equals(e.getStatus())
                    || !StringUtils.hasText(e.getStateValue())) {
                continue;
            }
            String v = e.getStateValue().strip();
            byValue.computeIfAbsent(v, k -> new ArrayList<>()).add(e);
        }
        int voided = 0;
        for (List<UserContextStateEntity> group : byValue.values()) {
            if (group.size() < 2) {
                continue;
            }
            group.sort(Comparator
                    .comparingInt((UserContextStateEntity e) -> kindSpecificity(e.getKind())).reversed()
                    .thenComparingDouble(UserContextStateEntity::getConfidence).reversed()
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

    /** kind 语义具体性：见 {@link ContextKind#specificity()}。 */
    static int kindSpecificity(String kind) {
        ContextKind ck = ContextKind.fromWire(kind);
        return ck != null ? ck.specificity() : 0;
    }

    public int voidJunk(String userId, String tenantId, Instant now) {
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
}
