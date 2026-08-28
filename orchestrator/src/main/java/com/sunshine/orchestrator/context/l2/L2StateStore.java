package com.sunshine.orchestrator.context.l2;

import com.sunshine.orchestrator.context.ContextProperties;
import com.sunshine.orchestrator.context.ContextWritePolicy;
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
    private final L2SemanticMergeService semanticMergeService;

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
        upsert(userId, tenantId, candidate, sourceMsgId, now, null);
    }

    /** user 维度带业务场景作用域落库：scene 非空时偏好打 {@code biz_scene_scope}（authority §5.5 ④）。 */
    public void upsert(
            String userId,
            String tenantId,
            L2ConflictMerger.Candidate candidate,
            String sourceMsgId,
            Instant now,
            String bizSceneScope) {
        if (!StringUtils.hasText(userId) || candidate == null) {
            return;
        }
        upsertInternal(null, userId, tenantId, candidate, sourceMsgId, now, bizSceneScope);
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
        upsertInternal(workspaceId, null, tenantId, candidate, sourceMsgId, now, null);
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
            upsertInternal(workspaceId, userId, tid, c, sourceMsgId, clock, null);
        }
    }

    private void upsertInternal(
            String workspaceId,
            String userId,
            String tenantId,
            L2ConflictMerger.Candidate candidate,
            String sourceMsgId,
            Instant now,
            String bizSceneScope) {
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
        if (existing != null) {
            // ① 字面快路径：同 key 命中即返回，不触发语义判定（§6.4）
            if (sameValue(existing.getStateValue(), value)) {
                refreshSameValue(existing, candidate, sourceMsgId, clock);
                return;
            }
            if (merger.decide(existing, candidate, contextProperties.getL2()) == L2ConflictMerger.Decision.REJECT) {
                log.debug("[ContextL2] reject overwrite scope={} kind={} key={} conf={}",
                        scopeLog(workspaceId, userId), kind, key, candidate.confidence());
                return;
            }
            existing.setStatus("superseded");
            existing.setUpdatedAt(clock);
            repository.save(existing);
            insertNewRow(workspaceId, userId, tid, kind, key, value, candidate.background(),
                    candidate.confidence(), "active", sourceMsgId, clock, bizSceneScope);
            return;
        }
        // ②③ 语义 merge（§6.4）：字面未命中 + 同 kind 有其他 active → LLM 判定；其余正常新增
        L2SemanticMergeService.Verdict verdict = semanticVerdict(workspaceId, userId, tid, kind, candidate);
        switch (verdict.action()) {
            case MERGE -> {
                applySemanticMerge(verdict, workspaceId, userId, tid, kind, candidate, sourceMsgId, clock);
                return;
            }
            case UPDATE -> {
                if (markActiveTarget(verdict.targetId(), "superseded", clock)) {
                    insertNewRow(workspaceId, userId, tid, kind, key, value, candidate.background(),
                            candidate.confidence(), "active", sourceMsgId, clock, bizSceneScope);
                    return;
                }
                // target 已非 active（竞态）→ 旧条不再构成当前陈述，正常新增
            }
            case CONFLICT -> {
                if (markActiveTarget(verdict.targetId(), "conflict", clock)) {
                    insertNewRow(workspaceId, userId, tid, kind, key, value, candidate.background(),
                            candidate.confidence(), "conflict", sourceMsgId, clock, bizSceneScope);
                    return;
                }
                // target 已非 active（竞态）→ 矛盾不成立，正常新增
            }
            default -> {
                // NOOP：与既有语义无关，正常新增
            }
        }
        insertNewRow(workspaceId, userId, tid, kind, key, value, candidate.background(),
                candidate.confidence(), "active", sourceMsgId, clock, bizSceneScope);
    }

    /**
     * ② 语义候选检索 + ③ 判定门控（§6.4）：开关关 / task.* 结构键（M2 导出按字面全量对比管理）不走语义路径；
     * 全量 active 候选（跨 kind，含同义重复如 fact/travel.origin 与 profile/location.current_city）；
     * 无 active 候选时零 LLM 成本。任何失败由 {@link L2SemanticMergeService} 回退 NOOP。
     */
    private L2SemanticMergeService.Verdict semanticVerdict(
            String workspaceId, String userId, String tid, String kind, L2ConflictMerger.Candidate candidate) {
        if (!contextProperties.getL2().isSemanticMergeEnabled()) {
            return L2SemanticMergeService.Verdict.noop("disabled");
        }
        String key = candidate.key().strip();
        if (key.startsWith("task.")) {
            return L2SemanticMergeService.Verdict.noop("task.* 结构键");
        }
        // 跨 kind 全量 active 候选：同义事实可能落不同 kind（如 fact/travel.origin 与 profile/location.current_city）
        List<UserContextStateEntity> allActive = workspaceId != null
                ? repository.findByWorkspaceIdAndTenantIdAndStatus(workspaceId, tid, "active")
                : repository.findByUserIdAndTenantIdAndStatus(userId, tid, "active");
        List<UserContextStateEntity> candidates = new ArrayList<>();
        if (allActive != null) {
            for (UserContextStateEntity e : allActive) {
                if (e == null || !StringUtils.hasText(e.getStateKey()) || e.getStateKey().startsWith("task.")) {
                    continue;
                }
                candidates.add(e);
            }
        }
        if (candidates.isEmpty()) {
            return L2SemanticMergeService.Verdict.noop("无 active 候选");
        }
        return semanticMergeService.judge(candidate, candidates);
    }

    /** MERGE：归一到 target（刷新 key/value/background + 置信取高），不产生 superseded。 */
    private void applySemanticMerge(
            L2SemanticMergeService.Verdict verdict,
            String workspaceId,
            String userId,
            String tid,
            String kind,
            L2ConflictMerger.Candidate candidate,
            String sourceMsgId,
            Instant clock) {
        Optional<UserContextStateEntity> targetOpt = repository.findById(verdict.targetId());
        if (targetOpt.isEmpty() || !"active".equals(targetOpt.get().getStatus())) {
            insertNewRow(workspaceId, userId, tid, kind, candidate.key().strip(), candidate.value().strip(),
                    candidate.background(), candidate.confidence(), "active", sourceMsgId, clock, null);
            return;
        }
        UserContextStateEntity target = targetOpt.get();
        if (StringUtils.hasText(verdict.mergedKey())) {
            target.setStateKey(verdict.mergedKey());
        }
        target.setStateValue(StringUtils.hasText(verdict.mergedValue())
                ? verdict.mergedValue()
                : candidate.value().strip());
        if (StringUtils.hasText(verdict.mergedBackground())) {
            target.setBackground(verdict.mergedBackground());
        } else if (StringUtils.hasText(candidate.background())) {
            target.setBackground(candidate.background());
        }
        if (candidate.confidence() > target.getConfidence()) {
            target.setConfidence(candidate.confidence());
        }
        target.setUpdatedAt(clock);
        if (StringUtils.hasText(sourceMsgId)) {
            target.setSourceMsgId(sourceMsgId);
        }
        repository.save(target);
        log.debug("[ContextL2] semantic MERGE target={} key={} reason={}",
                target.getId(), target.getStateKey(), verdict.reason());
    }

    /** 将仍 active 的 target 置为指定状态；返回是否命中（竞态下 target 可能已被置位）。 */
    private boolean markActiveTarget(String targetId, String status, Instant clock) {
        Optional<UserContextStateEntity> targetOpt = repository.findById(targetId);
        if (targetOpt.isEmpty() || !"active".equals(targetOpt.get().getStatus())) {
            return false;
        }
        UserContextStateEntity target = targetOpt.get();
        target.setStatus(status);
        target.setUpdatedAt(clock);
        repository.save(target);
        return true;
    }

    private void insertNewRow(
            String workspaceId,
            String userId,
            String tid,
            String kind,
            String key,
            String value,
            String background,
            double confidence,
            String status,
            String sourceMsgId,
            Instant clock,
            String bizSceneScope) {
        UserContextStateEntity neu = new UserContextStateEntity();
        neu.setId(newId());
        neu.setScope(workspaceId != null ? "workspace" : "user");
        neu.setUserId(workspaceId != null ? "" : userId);
        neu.setWorkspaceId(workspaceId);
        neu.setTenantId(tid);
        neu.setKind(kind);
        neu.setStateKey(key);
        neu.setStateValue(value);
        neu.setBackground(background);
        neu.setConfidence(confidence);
        neu.setStatus(status);
        neu.setBizSceneScope(StringUtils.hasText(bizSceneScope) ? bizSceneScope : "*");
        neu.setExpiresAt(expiresAtFor(kind, clock));
        neu.setSourceMsgId(sourceMsgId);
        neu.setCreatedAt(clock);
        neu.setUpdatedAt(clock);
        repository.save(neu);
    }

    private static String scopeLog(String workspaceId, String userId) {
        return workspaceId != null ? "workspace:" + workspaceId : "user:" + userId;
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

    /**
     * 同 key+value：零增益（无更高置信 / 无新背景 / 无新溯源）→ **跳过写库**（幂等，§5.5.6 ⑦）。
     * 有任一增益才刷新 {@code updatedAt} 与对应字段，不产生 superseded。
     */
    private void refreshSameValue(
            UserContextStateEntity existing,
            L2ConflictMerger.Candidate candidate,
            String sourceMsgId,
            Instant clock) {
        boolean changed = false;
        if (candidate.confidence() > existing.getConfidence()) {
            existing.setConfidence(candidate.confidence());
            changed = true;
        }
        if (StringUtils.hasText(candidate.background())
                && !stripOrEmpty(candidate.background()).equals(stripOrEmpty(existing.getBackground()))) {
            existing.setBackground(candidate.background());
            changed = true;
        }
        if (StringUtils.hasText(sourceMsgId) && !sourceMsgId.equals(existing.getSourceMsgId())) {
            existing.setSourceMsgId(sourceMsgId);
            changed = true;
        }
        if (!changed) {
            log.debug("[ContextL2] skip idempotent same value id={} key={}",
                    existing.getId(), existing.getStateKey());
            return;
        }
        existing.setUpdatedAt(clock);
        repository.save(existing);
        log.debug("[ContextL2] refresh same value id={} key={}", existing.getId(), existing.getStateKey());
    }

    private static String stripOrEmpty(String s) {
        return StringUtils.hasText(s) ? s.strip() : "";
    }

    static boolean sameValue(String a, String b) {
        if (!StringUtils.hasText(a) || !StringUtils.hasText(b)) {
            return false;
        }
        return a.strip().equals(b.strip());
    }

    Instant expiresAtFor(String kind, Instant now) {
        int days = ContextWritePolicy.l2TtlDays(kind, contextProperties.getL2());
        if (days <= 0) {
            return null;
        }
        return now.plus(days, ChronoUnit.DAYS);
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
