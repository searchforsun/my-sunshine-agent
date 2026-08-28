package com.sunshine.orchestrator.context.l2;

import com.sunshine.orchestrator.context.ContextProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class L2StateStoreFilterTest {

    @Mock
    private UserContextStateRepository repository;

    private ContextProperties properties;
    private L2StateStore store;
    private Instant now;

    @BeforeEach
    void setUp() {
        properties = new ContextProperties();
        // 既有测试覆盖字面路径：语义判定一律 NOOP（专项覆盖见 L2StateStoreSemanticMergeTest）
        L2SemanticMergeService noopMerge = new L2SemanticMergeService(null, null) {
            @Override
            public Verdict judge(L2ConflictMerger.Candidate candidate, List<UserContextStateEntity> rows) {
                return Verdict.noop("test");
            }
        };
        store = new L2StateStore(repository, new L2ConflictMerger(), properties, noopMerge);
        now = Instant.parse("2026-07-22T03:00:00Z");
    }

    @Test
    void filterActive_excludesExpired_keepsUnexpiredConstraint() {
        UserContextStateEntity expiredPref = entity("1", "preference", "style", "详细", 0.9,
                now.minus(1, ChronoUnit.HOURS));
        UserContextStateEntity liveConstraint = entity("2", "constraint", "budget", "单次不超过500", 0.95,
                now.plus(10, ChronoUnit.DAYS));
        when(repository.findByUserIdAndTenantIdAndStatus("u1", "default", "active"))
                .thenReturn(List.of(expiredPref, liveConstraint));

        List<UserContextStateEntity> live = store.listInjectable("u1", "default", now);

        assertThat(live).hasSize(1);
        assertThat(live.get(0).getKind()).isEqualTo("constraint");
        assertThat(live.get(0).getStateKey()).isEqualTo("budget");
    }

    @Test
    void renderSystemBlock_formatsKindKeyValue() {
        UserContextStateEntity pref = entity("1", "preference", "style", "简洁", 0.9, null);
        UserContextStateEntity constraint = entity("2", "constraint", "budget", "单次不超过500", 0.95,
                now.plus(5, ChronoUnit.DAYS));

        String block = L2StateStore.renderSystemBlock(List.of(pref, constraint));

        assertThat(block).isEqualTo("""
                [用户状态 · L2]
                - preference/style: 简洁
                - constraint/budget: 单次不超过500""");
    }

    @Test
    void upsert_sameValue_refreshesUpdatedAt_doesNotSupersede() {
        UserContextStateEntity old = entity("old", "profile", "name", "测一", 0.9, null);
        Instant oldUpdated = old.getUpdatedAt();
        when(repository.findByUserIdAndTenantIdAndKindAndStateKeyAndStatus(
                "u1", "default", "profile", "name", "active"))
                .thenReturn(Optional.of(old));

        store.upsert("u1", "default",
                new L2ConflictMerger.Candidate("profile", "name", "测一", 0.95),
                "msg-same", now);

        assertThat(old.getStatus()).isEqualTo("active");
        assertThat(old.getStateValue()).isEqualTo("测一");
        assertThat(old.getUpdatedAt()).isEqualTo(now);
        assertThat(old.getUpdatedAt()).isAfter(oldUpdated);
        assertThat(old.getConfidence()).isEqualTo(0.95);
        assertThat(old.getSourceMsgId()).isEqualTo("msg-same");
        verify(repository, times(1)).save(old);
    }

    @Test
    void upsert_sameValueNoGain_skipsWrite() {
        // ⑦ 幂等：同 key+value 且无增益（无更高置信/无新背景/无新溯源）→ 零写库
        UserContextStateEntity old = entity("old", "profile", "name", "测一", 0.9, null);
        old.setSourceMsgId("msg-same");
        old.setBackground("本名");
        Instant oldUpdated = old.getUpdatedAt();
        when(repository.findByUserIdAndTenantIdAndKindAndStateKeyAndStatus(
                "u1", "default", "profile", "name", "active"))
                .thenReturn(Optional.of(old));

        store.upsert("u1", "default",
                new L2ConflictMerger.Candidate("profile", "name", "测一", 0.9, "本名", "active"),
                "msg-same", now);

        assertThat(old.getStatus()).isEqualTo("active");
        assertThat(old.getUpdatedAt()).isEqualTo(oldUpdated);
        verify(repository, never()).save(any());
    }

    @Test
    void upsert_preference_supersedesOldWhenAccepted() {
        UserContextStateEntity old = entity("old", "preference", "style", "详细", 0.8, null);
        when(repository.findByUserIdAndTenantIdAndKindAndStateKeyAndStatus(
                "u1", "default", "preference", "style", "active"))
                .thenReturn(Optional.of(old));

        store.upsert("u1", "default",
                new L2ConflictMerger.Candidate("preference", "style", "简洁", 0.85),
                "msg-1", now);

        assertThat(old.getStatus()).isEqualTo("superseded");
        ArgumentCaptor<UserContextStateEntity> cap = ArgumentCaptor.forClass(UserContextStateEntity.class);
        verify(repository, times(2)).save(cap.capture());
        List<UserContextStateEntity> saved = cap.getAllValues();
        assertThat(saved.get(0).getStatus()).isEqualTo("superseded");
        UserContextStateEntity neu = saved.get(1);
        assertThat(neu.getStatus()).isEqualTo("active");
        assertThat(neu.getStateValue()).isEqualTo("简洁");
        assertThat(neu.getConfidence()).isEqualTo(0.85);
        assertThat(neu.getSourceMsgId()).isEqualTo("msg-1");
    }

    @Test
    void upsert_constraint_rejectsBelowOverwriteBar() {
        UserContextStateEntity old = entity("old", "constraint", "budget", "单次不超过500", 0.9, null);
        when(repository.findByUserIdAndTenantIdAndKindAndStateKeyAndStatus(
                "u1", "default", "constraint", "budget", "active"))
                .thenReturn(Optional.of(old));

        store.upsert("u1", "default",
                new L2ConflictMerger.Candidate("constraint", "budget", "单次不超过800", 0.8),
                "msg-2", now);

        assertThat(old.getStatus()).isEqualTo("active");
        verify(repository, never()).save(any());
    }

    @Test
    void assembleWorkspaceBlock_readsOnlyWorkspaceScope() {
        UserContextStateEntity wsTodo = wsEntity("w1", "todo", "collect-receipts", "收齐报销发票", 0.9, null);
        wsTodo.setBackground("财务报销项目收尾");
        UserContextStateEntity wsPref = wsEntity("w2", "preference", "output", "表格优先", 0.85, null);
        when(repository.findByWorkspaceIdAndTenantIdAndStatus("ws-1", "default", "active"))
                .thenReturn(List.of(wsTodo, wsPref));

        String block = store.assembleWorkspaceBlock("ws-1", "default");

        assertThat(block)
                .contains("- todo/collect-receipts: 收齐报销发票 （背景：财务报销项目收尾）")
                .contains("- preference/output: 表格优先");
        verify(repository, never()).findByUserIdAndTenantIdAndStatus(anyString(), anyString(), anyString());
    }

    @Test
    void upsertWorkspace_persistsWorkspaceScopeRow() {
        when(repository.findByWorkspaceIdAndTenantIdAndKindAndStateKeyAndStatus(
                "ws-1", "default", "todo", "collect-receipts", "active"))
                .thenReturn(Optional.empty());

        store.upsertWorkspace("ws-1", "default",
                new L2ConflictMerger.Candidate("todo", "collect-receipts", "收齐报销发票", 0.9),
                "msg-ws", now);

        ArgumentCaptor<UserContextStateEntity> cap = ArgumentCaptor.forClass(UserContextStateEntity.class);
        verify(repository).save(cap.capture());
        UserContextStateEntity saved = cap.getValue();
        assertThat(saved.getScope()).isEqualTo("workspace");
        assertThat(saved.getWorkspaceId()).isEqualTo("ws-1");
        assertThat(saved.getUserId()).isEqualTo("");
        assertThat(saved.getStatus()).isEqualTo("active");
        assertThat(saved.getSourceMsgId()).isEqualTo("msg-ws");
    }

    @Test
    void renderSystemBlock_showsBackgroundWhenPresent() {
        UserContextStateEntity withBg = entity("1", "preference", "style", "简洁", 0.9, null);
        withBg.setBackground("面向财务同事演示");
        UserContextStateEntity withoutBg = entity("2", "constraint", "budget", "单次不超过500", 0.95,
                now.plus(5, ChronoUnit.DAYS));

        String block = L2StateStore.renderSystemBlock(List.of(withBg, withoutBg));

        assertThat(block).isEqualTo("""
                [用户状态 · L2]
                - preference/style: 简洁 （背景：面向财务同事演示）
                - constraint/budget: 单次不超过500""");
    }

    @Test
    void ttlDays_forConstraintUsesConfiguredDays() {
        Instant expires = store.expiresAtFor("constraint", now);
        assertThat(expires).isEqualTo(now.plus(30, ChronoUnit.DAYS));
    }

    @Test
    void ttlDays_forTodoUsesConfiguredDays() {
        Instant expires = store.expiresAtFor("todo", now);
        assertThat(expires).isEqualTo(now.plus(7, ChronoUnit.DAYS));
    }

    @Test
    void upsert_todoDone_voidsActiveRowWithoutInsert() {
        UserContextStateEntity active = entity("old", "todo", "finance.pending_approval", "跟进审批单 PR-2026-0812", 0.9, null);
        when(repository.findByUserIdAndTenantIdAndKindAndStateKeyAndStatus(
                "u1", "default", "todo", "finance.pending_approval", "active"))
                .thenReturn(Optional.of(active));

        store.upsert("u1", "default",
                new L2ConflictMerger.Candidate("todo", "finance.pending_approval", "跟进审批单 PR-2026-0812",
                        0.9, "OA 审批", "done"),
                "msg-done", now);

        assertThat(active.getStatus()).isEqualTo("void");
        assertThat(active.getUpdatedAt()).isEqualTo(now);
        verify(repository, times(1)).save(active);
    }

    @Test
    void upsert_todoVoidWithoutActiveRow_doesNothing() {
        when(repository.findByUserIdAndTenantIdAndKindAndStateKeyAndStatus(
                "u1", "default", "todo", "finance.dropped", "active"))
                .thenReturn(Optional.empty());

        store.upsert("u1", "default",
                new L2ConflictMerger.Candidate("todo", "finance.dropped", "已不跟进", 0.9, "OA 审批", "void"),
                "msg-void", now);

        verify(repository, never()).save(any());
    }

    @Test
    void upsert_todoActive_skipsResurrectWhenVoidedByNewerMessage() {
        // 乱序保护：同 key 已被更晚消息 void（void 时间晚于候选消息），旧抽取的 active 候选不得复活
        UserContextStateEntity voided = entity("old", "todo", "approval.pr_2026_0812_follow_up",
                "跟进审批单 PR-2026-0812", 0.9, null);
        voided.setStatus("void");
        voided.setUpdatedAt(now.plus(1, ChronoUnit.HOURS));
        when(repository.findFirstByUserIdAndTenantIdAndKindAndStateKeyAndStatusOrderByUpdatedAtDesc(
                "u1", "default", "todo", "approval.pr_2026_0812_follow_up", "void"))
                .thenReturn(Optional.of(voided));

        store.upsert("u1", "default",
                new L2ConflictMerger.Candidate("todo", "approval.pr_2026_0812_follow_up",
                        "跟进审批单 PR-2026-0812", 0.9, "OA 审批", "active"),
                "msg-older", now);

        verify(repository, never()).save(any());
    }

    @Test
    void upsert_todoActive_allowsResurrectWhenVoidOlderThanCandidate() {
        // 乱序保护不误伤：void 早于候选消息时间（如 TTL 过期后用户重新提出）→ 允许重新 active
        UserContextStateEntity voided = entity("old", "todo", "approval.pr_2026_0812_follow_up",
                "跟进审批单 PR-2026-0812", 0.9, null);
        voided.setStatus("void");
        voided.setUpdatedAt(now.minus(1, ChronoUnit.HOURS));
        when(repository.findFirstByUserIdAndTenantIdAndKindAndStateKeyAndStatusOrderByUpdatedAtDesc(
                "u1", "default", "todo", "approval.pr_2026_0812_follow_up", "void"))
                .thenReturn(Optional.of(voided));
        when(repository.findByUserIdAndTenantIdAndKindAndStateKeyAndStatus(
                "u1", "default", "todo", "approval.pr_2026_0812_follow_up", "active"))
                .thenReturn(Optional.empty());

        store.upsert("u1", "default",
                new L2ConflictMerger.Candidate("todo", "approval.pr_2026_0812_follow_up",
                        "跟进审批单 PR-2026-0812", 0.9, "OA 审批", "active"),
                "msg-newer", now);

        ArgumentCaptor<UserContextStateEntity> cap = ArgumentCaptor.forClass(UserContextStateEntity.class);
        verify(repository).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo("active");
        assertThat(cap.getValue().getSourceMsgId()).isEqualTo("msg-newer");
    }

    @Test
    void upsertWorkspace_todoDone_voidsWorkspaceActiveRow() {
        UserContextStateEntity active = wsEntity("old", "todo", "finance.pending_approval", "跟进审批单 PR-2026-0812", 0.9, null);
        when(repository.findByWorkspaceIdAndTenantIdAndKindAndStateKeyAndStatus(
                "ws-1", "default", "todo", "finance.pending_approval", "active"))
                .thenReturn(Optional.of(active));

        store.upsertWorkspace("ws-1", "default",
                new L2ConflictMerger.Candidate("todo", "finance.pending_approval", "跟进审批单 PR-2026-0812",
                        0.9, "OA 审批", "done"),
                "msg-done", now);

        assertThat(active.getStatus()).isEqualTo("void");
        verify(repository, times(1)).save(active);
    }

    @Test
    void upsert_newTodo_persistsBackground() {
        when(repository.findByUserIdAndTenantIdAndKindAndStateKeyAndStatus(
                "u1", "default", "todo", "finance.pending_approval", "active"))
                .thenReturn(Optional.empty());

        store.upsert("u1", "default",
                new L2ConflictMerger.Candidate("todo", "finance.pending_approval", "跟进审批单 PR-2026-0812",
                        0.9, "OA 审批", "active"),
                "msg-bg", now);

        ArgumentCaptor<UserContextStateEntity> cap = ArgumentCaptor.forClass(UserContextStateEntity.class);
        verify(repository).save(cap.capture());
        assertThat(cap.getValue().getBackground()).isEqualTo("OA 审批");
    }

    @Test
    void upsert_sameValue_refreshesBackground() {
        UserContextStateEntity old = entity("old", "todo", "finance.pending_approval", "跟进审批单 PR-2026-0812", 0.9, null);
        when(repository.findByUserIdAndTenantIdAndKindAndStateKeyAndStatus(
                "u1", "default", "todo", "finance.pending_approval", "active"))
                .thenReturn(Optional.of(old));

        store.upsert("u1", "default",
                new L2ConflictMerger.Candidate("todo", "finance.pending_approval", "跟进审批单 PR-2026-0812",
                        0.9, "OA 审批窗口期", "active"),
                "msg-bg2", now);

        assertThat(old.getStatus()).isEqualTo("active");
        assertThat(old.getBackground()).isEqualTo("OA 审批窗口期");
    }

    @Test
    void upsert_nonTodoDoneDoesNotVoidActiveRow() {
        // P2-2：status 生命周期仅 todo；非 todo 即使传 done 也走正常合并，不静默 void 既有 chat 行
        UserContextStateEntity old = entity("old", "preference", "style", "简洁", 0.85, null);
        when(repository.findByUserIdAndTenantIdAndKindAndStateKeyAndStatus(
                "u1", "default", "preference", "style", "active"))
                .thenReturn(Optional.of(old));

        store.upsert("u1", "default",
                new L2ConflictMerger.Candidate("preference", "style", "简洁", 0.9, null, "done"),
                "msg-done", now);

        assertThat(old.getStatus()).isEqualTo("active");
        assertThat(old.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void upsertWorkspace_newTodo_persistsBackground() {
        when(repository.findByWorkspaceIdAndTenantIdAndKindAndStateKeyAndStatus(
                "ws-1", "default", "todo", "finance.pending_approval", "active"))
                .thenReturn(Optional.empty());

        store.upsertWorkspace("ws-1", "default",
                new L2ConflictMerger.Candidate("todo", "finance.pending_approval", "跟进审批单 PR-2026-0812",
                        0.9, "OA 审批", "active"),
                "msg-ws-bg", now);

        ArgumentCaptor<UserContextStateEntity> cap = ArgumentCaptor.forClass(UserContextStateEntity.class);
        verify(repository).save(cap.capture());
        assertThat(cap.getValue().getBackground()).isEqualTo("OA 审批");
    }

    @Test
    void syncTodoExport_upsertsPending_voidsStaleTaskPrefixRows() {
        UserContextStateEntity t1 = entity("t1", "todo", "task.a1b2c3d4.t1", "部署 QA 环境", 1.0, null);
        UserContextStateEntity t2 = entity("t2", "todo", "task.a1b2c3d4.t2", "回归测试", 1.0, null);
        when(repository.findByUserIdAndTenantIdAndKindAndStateKeyStartingWithAndStatus(
                "u1", "default", "todo", "task.", "active"))
                .thenReturn(List.of(t1, t2));
        when(repository.findByUserIdAndTenantIdAndKindAndStateKeyAndStatus(
                "u1", "default", "todo", "task.a1b2c3d4.t1", "active"))
                .thenReturn(Optional.of(t1));

        store.syncTodoExport("u1", "default",
                List.of(new L2ConflictMerger.Candidate(
                        "todo", "task.a1b2c3d4.t1", "部署 QA 环境", 1.0, "目标", "active")),
                "msg-export", now);

        assertThat(t2.getStatus()).isEqualTo("void");
        assertThat(t1.getStatus()).isEqualTo("active");
        assertThat(t1.getUpdatedAt()).isEqualTo(now);
        verify(repository).save(t2);
    }

    @Test
    void syncTodoExportWorkspace_pendingEmpty_voidsAllTaskPrefixRows() {
        UserContextStateEntity t1 = wsEntity("t1", "todo", "task.a1b2c3d4.t1", "部署 QA 环境", 1.0, null);
        UserContextStateEntity t2 = wsEntity("t2", "todo", "task.b2c3d4e5.t2", "回归测试", 1.0, null);
        when(repository.findByWorkspaceIdAndTenantIdAndKindAndStateKeyStartingWithAndStatus(
                "ws-1", "default", "todo", "task.", "active"))
                .thenReturn(List.of(t1, t2));

        store.syncTodoExportWorkspace("ws-1", "default", List.of(), "msg-export", now);

        assertThat(t1.getStatus()).isEqualTo("void");
        assertThat(t2.getStatus()).isEqualTo("void");
        verify(repository, times(2)).save(any());
    }

    @Test
    void syncTodoExport_usesTaskPrefixQueryOnly() {
        when(repository.findByUserIdAndTenantIdAndKindAndStateKeyStartingWithAndStatus(
                "u1", "default", "todo", "task.", "active"))
                .thenReturn(List.of());

        store.syncTodoExport("u1", "default",
                List.of(new L2ConflictMerger.Candidate(
                        "todo", "task.a1b2c3d4.t1", "部署 QA 环境", 1.0, "目标", "active")),
                "msg-export", now);

        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(repository).findByUserIdAndTenantIdAndKindAndStateKeyStartingWithAndStatus(
                eq("u1"), eq("default"), eq("todo"), cap.capture(), eq("active"));
        assertThat(cap.getValue()).isEqualTo("task.");
    }

    private static UserContextStateEntity wsEntity(
            String id, String kind, String key, String value, double conf, Instant expiresAt) {
        UserContextStateEntity e = entity(id, kind, key, value, conf, expiresAt);
        e.setUserId("");
        e.setWorkspaceId("ws-1");
        return e;
    }

    private static UserContextStateEntity entity(
            String id, String kind, String key, String value, double conf, Instant expiresAt) {
        UserContextStateEntity e = new UserContextStateEntity();
        e.setId(id);
        e.setUserId("u1");
        e.setTenantId("default");
        e.setKind(kind);
        e.setStateKey(key);
        e.setStateValue(value);
        e.setConfidence(conf);
        e.setStatus("active");
        e.setExpiresAt(expiresAt);
        e.setCreatedAt(Instant.parse("2026-07-01T00:00:00Z"));
        e.setUpdatedAt(Instant.parse("2026-07-01T00:00:00Z"));
        return e;
    }
}
