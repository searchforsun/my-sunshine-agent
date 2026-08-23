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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
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
        return listInjectableInternal(userId, null, tenantId, now);
    }

    /** workspace scope：仅命中 workspace_id 维度，user 行不参与。 */
    public List<UserContextStateEntity> listInjectableWorkspace(String workspaceId, String tenantId, Instant now) {
        if (!StringUtils.hasText(workspaceId)) {
            return List.of();
        }
        return listInjectableInternal(null, workspaceId, tenantId, now);
    }

    private List<UserContextStateEntity> listInjectableInternal(
            String userId, String workspaceId, String tenantId, Instant now) {
        String tid = tenantId != null ? tenantId : "default";
        Instant clock = now != null ? now : Instant.now();
        List<UserContextStateEntity> active = workspaceId != null
                ? repository.findByWorkspaceIdAndTenantIdAndStatus(workspaceId, tid, "active")
                : repository.findByUserIdAndTenantIdAndStatus(userId, tid, "active");
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

    /** workspace scope 的 L2 块；workspaceId 空/blank → 空串。 */
    public String assembleWorkspaceBlock(String workspaceId, String tenantId) {
        if (!StringUtils.hasText(workspaceId)) {
            return "";
        }
        List<UserContextStateEntity> entries = listInjectableWorkspace(workspaceId, tenantId, Instant.now());
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
            if (StringUtils.hasText(e.getBackground())) {
                sb.append(" （背景：").append(e.getBackground()).append('）');
            }
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
        upsertInternal(null, userId, tenantId, candidate, sourceMsgId, now);
    }

    /** workspace scope 落库：workspace_id 维度，冲突/刷新/落库逻辑与 user 路径一致。 */
    public void upsertWorkspace(
            String workspaceId,
            String tenantId,
            L2ConflictMerger.Candidate candidate,
            String sourceMsgId,
            Instant now) {
        if (!StringUtils.hasText(workspaceId) || candidate == null) {
            return;
        }
        upsertInternal(workspaceId, null, tenantId, candidate, sourceMsgId, now);
    }

    /**
     * 结构导出全量对比（user 维度）：pending 集合内的 todo 逐条 upsert；
     * 该 scope 下 kind=todo 且 key 以 {@code task.} 开头、但不在 pending 集合的 active 行显式 void。
     * 幂等：同 key 重复导出刷新；全部完成（pending 空）→ task.* 全 void；换题（goal 变）→ 旧前缀残留被清。
     */
    public void syncTodoExport(
            String userId,
            String tenantId,
            List<L2ConflictMerger.Candidate> pending,
            String sourceMsgId,
            Instant now) {
        if (!StringUtils.hasText(userId) || pending == null) {
            return;
        }
        syncTodoExportInternal(null, userId, tenantId, pending, sourceMsgId, now);
    }

    /** workspace 维度结构导出全量对比。 */
    public void syncTodoExportWorkspace(
            String workspaceId,
            String tenantId,
            List<L2ConflictMerger.Candidate> pending,
            String sourceMsgId,
            Instant now) {
        if (!StringUtils.hasText(workspaceId) || pending == null) {
            return;
        }
        syncTodoExportInternal(workspaceId, null, tenantId, pending, sourceMsgId, now);
    }

    private void syncTodoExportInternal(
            String workspaceId,
            String userId,
            String tenantId,
            List<L2ConflictMerger.Candidate> pending,
            String sourceMsgId,
            Instant now) {
        String tid = tenantId != null ? tenantId : "default";
        Instant clock = now != null ? now : Instant.now();
        Set<String> pendingKeys = new HashSet<>();
        for (L2ConflictMerger.Candidate c : pending) {
            if (c != null && StringUtils.hasText(c.key())) {
                pendingKeys.add(c.key().strip());
            }
        }
        String prefix = "task.";
        List<UserContextStateEntity> activeTaskRows = workspaceId != null
                ? repository.findByWorkspaceIdAndTenantIdAndKindAndStateKeyStartingWithAndStatus(
                        workspaceId, tid, "todo", prefix, "active")
                : repository.findByUserIdAndTenantIdAndKindAndStateKeyStartingWithAndStatus(
                        userId, tid, "todo", prefix, "active");
        if (activeTaskRows != null) {
            for (UserContextStateEntity e : activeTaskRows) {
                if (e == null || pendingKeys.contains(e.getStateKey())) {
                    continue;
                }
                e.setStatus("void");
                e.setUpdatedAt(clock);
                repository.save(e);
                log.debug("[ContextL2] syncTodoExport void stale id={} key={}", e.getId(), e.getStateKey());
            }
        }
        for (L2ConflictMerger.Candidate c : pending) {
            if (c == null) {
                continue;
            }
            upsertInternal(workspaceId, userId, tid, c, sourceMsgId, clock);
        }
    }

    private void upsertInternal(
            String workspaceId,
            String userId,
            String tenantId,
            L2ConflictMerger.Candidate candidate,
            String sourceMsgId,
            Instant now) {
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
        String status = L2ConflictMerger.normalizeStatus(candidate.status());
        // status 生命周期仅 todo 类：done/void 对既有 active 行显式失效；其他 kind 固定 active 走正常合并
        if ("todo".equals(kind) && ("done".equals(status) || "void".equals(status))) {
            voidActiveRow(workspaceId, userId, tid, kind, key, clock);
            return;
        }
        // 乱序保护（仅 todo，其他 kind 无 done/void 生命周期）：异步抽取乱序时，
        // 若同 key 已被更晚消息 void（void 时间晚于本候选消息），不得复活
        if ("todo".equals(kind) && isVoidedAfter(workspaceId, userId, tid, kind, key, clock)) {
            log.debug("[ContextL2] skip resurrect scope={} kind={} key={}（已被更晚消息 void）",
                    workspaceId != null ? "workspace:" + workspaceId : "user:" + userId, kind, key);
            return;
        }
        Optional<UserContextStateEntity> existingOpt = workspaceId != null
                ? repository.findByWorkspaceIdAndTenantIdAndKindAndStateKeyAndStatus(
                        workspaceId, tid, kind, key, "active")
                : repository.findByUserIdAndTenantIdAndKindAndStateKeyAndStatus(userId, tid, kind, key, "active");
        UserContextStateEntity existing = existingOpt.orElse(null);
        if (existing != null && sameValue(existing.getStateValue(), value)) {
            refreshSameValue(existing, candidate, sourceMsgId, clock);
            return;
        }
        L2ConflictMerger.Decision decision = merger.decide(existing, candidate, contextProperties.getL2());
        if (decision == L2ConflictMerger.Decision.REJECT) {
            log.debug("[ContextL2] reject overwrite scope={} kind={} key={} conf={}",
                    workspaceId != null ? "workspace:" + workspaceId : "user:" + userId,
                    kind, key, candidate.confidence());
            return;
        }
        if (existing != null) {
            existing.setStatus("superseded");
            existing.setUpdatedAt(clock);
            repository.save(existing);
        }
        UserContextStateEntity neu = new UserContextStateEntity();
        neu.setId(newId());
        neu.setScope(workspaceId != null ? "workspace" : "user");
        neu.setUserId(workspaceId != null ? "" : userId);
        neu.setWorkspaceId(workspaceId);
        neu.setTenantId(tid);
        neu.setKind(kind);
        neu.setStateKey(key);
        neu.setStateValue(value);
        neu.setBackground(candidate.background());
        neu.setConfidence(candidate.confidence());
        neu.setStatus("active");
        neu.setExpiresAt(expiresAtFor(kind, clock));
        neu.setSourceMsgId(sourceMsgId);
        neu.setCreatedAt(clock);
        neu.setUpdatedAt(clock);
        repository.save(neu);
    }

    /** 同 key 是否存在「void 时间晚于给定时钟」的 void 行（含 workspace/user 双维度）。 */
    private boolean isVoidedAfter(
            String workspaceId, String userId, String tid, String kind, String key, Instant clock) {
        Optional<UserContextStateEntity> voidOpt = workspaceId != null
                ? repository.findFirstByWorkspaceIdAndTenantIdAndKindAndStateKeyAndStatusOrderByUpdatedAtDesc(
                        workspaceId, tid, kind, key, "void")
                : repository.findFirstByUserIdAndTenantIdAndKindAndStateKeyAndStatusOrderByUpdatedAtDesc(
                        userId, tid, kind, key, "void");
        if (voidOpt.isEmpty()) {
            return false;
        }
        Instant voidAt = voidOpt.get().getUpdatedAt();
        return voidAt != null && voidAt.isAfter(clock);
    }

    /** done/void 候选：不新增，将同 scope+kind+key 的 active 行显式置 void（无 active 行则无操作）。 */
    private void voidActiveRow(
            String workspaceId, String userId, String tid, String kind, String key, Instant clock) {
        Optional<UserContextStateEntity> activeOpt = workspaceId != null
                ? repository.findByWorkspaceIdAndTenantIdAndKindAndStateKeyAndStatus(workspaceId, tid, kind, key, "active")
                : repository.findByUserIdAndTenantIdAndKindAndStateKeyAndStatus(userId, tid, kind, key, "active");
        activeOpt.ifPresent(existing -> {
            existing.setStatus("void");
            existing.setUpdatedAt(clock);
            repository.save(existing);
            log.debug("[ContextL2] void active row id={} kind={} key={}", existing.getId(), kind, key);
        });
    }

    /** 同 key+value：只刷新时间/背景（及溯源/更高置信），不产生 superseded。 */
    private void refreshSameValue(
            UserContextStateEntity existing,
            L2ConflictMerger.Candidate candidate,
            String sourceMsgId,
            Instant clock) {
        existing.setUpdatedAt(clock);
        if (candidate.confidence() > existing.getConfidence()) {
            existing.setConfidence(candidate.confidence());
        }
        if (StringUtils.hasText(candidate.background())) {
            existing.setBackground(candidate.background());
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
            case "todo" -> l2.getTodoTtlDays();
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
