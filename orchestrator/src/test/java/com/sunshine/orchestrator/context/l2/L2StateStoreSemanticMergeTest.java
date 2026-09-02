package com.sunshine.orchestrator.context.l2;

import com.sunshine.orchestrator.context.ContextProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** §6.4 三阶段写路径：字面快路径优先 / 语义四动作落库 / 门控（开关 / task.* / 竞态回退）。候选检索为跨 kind 全量 active（同义事实可能落不同 kind）。 */
@ExtendWith(MockitoExtension.class)
class L2StateStoreSemanticMergeTest {

    @Mock
    private UserContextStateRepository repository;

    private ContextProperties properties;
    private L2StateStore store;
    private L2SemanticMergeService semanticMerge;
    private Instant now;

    @BeforeEach
    void setUp() {
        properties = new ContextProperties();
        semanticMerge = new L2SemanticMergeService(null, null);
        store = new L2StateStore(repository, new L2ConflictMerger(), properties, semanticMerge);
        now = Instant.parse("2026-08-25T03:00:00Z");
    }

    private void stubSemanticVerdict(L2SemanticMergeService.Action action, String targetId) {
        semanticMerge = new L2SemanticMergeService(null, null) {
            @Override
            public Verdict judge(L2ConflictMerger.Candidate candidate, List<UserContextStateEntity> rows) {
                if (action == Action.NOOP) {
                    return Verdict.noop("test");
                }
                return new Verdict(action, targetId, "project.database", "项目数据库为 MySQL（归一）",
                        null, "test");
            }
        };
        store = new L2StateStore(repository, new L2ConflictMerger(), properties, semanticMerge);
    }

    private static UserContextStateEntity row(String id, String kind, String key, String value) {
        UserContextStateEntity e = new UserContextStateEntity();
        e.setId(id);
        e.setUserId("u1");
        e.setTenantId("default");
        e.setKind(kind);
        e.setStateKey(key);
        e.setStateValue(value);
        e.setConfidence(0.8);
        e.setStatus("active");
        e.setCreatedAt(Instant.parse("2026-08-01T00:00:00Z"));
        e.setUpdatedAt(Instant.parse("2026-08-01T00:00:00Z"));
        return e;
    }

    /** 字面快路径优先：同 key 命中不触发语义判定。 */
    @Test
    void upsert_literalHit_skipsSemanticJudge() {
        UserContextStateEntity existing = row("r1", "fact", "project.database", "项目数据库为 MySQL");
        when(repository.findByUserIdAndTenantIdAndKindAndStateKeyAndStatus(
                "u1", "default", "fact", "project.database", "active"))
                .thenReturn(Optional.of(existing));

        store.upsert("u1", "default",
                new L2ConflictMerger.Candidate("fact", "project.database", "项目数据库为 MySQL", 0.9),
                "msg-1", now);

        verify(repository, never()).findByUserIdAndTenantIdAndStatus(any(), any(), any());
        assertThat(existing.getStatus()).isEqualTo("active");
    }

    @Test
    void upsert_noop_insertsNewActiveRow() {
        when(repository.findByUserIdAndTenantIdAndKindAndStateKeyAndStatus(
                "u1", "default", "fact", "project.database", "active"))
                .thenReturn(Optional.empty());
        when(repository.findByUserIdAndTenantIdAndStatus("u1", "default", "active"))
                .thenReturn(List.of(row("r1", "fact", "project.db", "项目存储用 MySQL")));
        stubSemanticVerdict(L2SemanticMergeService.Action.NOOP, null);

        store.upsert("u1", "default",
                new L2ConflictMerger.Candidate("fact", "project.database", "项目数据库为 MySQL", 0.9),
                "msg-1", now);

        ArgumentCaptor<UserContextStateEntity> cap = ArgumentCaptor.forClass(UserContextStateEntity.class);
        verify(repository, times(1)).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo("active");
        assertThat(cap.getValue().getStateKey()).isEqualTo("project.database");
    }

    @Test
    void upsert_merge_refreshesTargetWithoutNewRowOrSupersede() {
        UserContextStateEntity target = row("r1", "fact", "project.db", "项目存储用 MySQL");
        when(repository.findByUserIdAndTenantIdAndKindAndStateKeyAndStatus(
                "u1", "default", "fact", "project.database", "active"))
                .thenReturn(Optional.empty());
        when(repository.findByUserIdAndTenantIdAndStatus("u1", "default", "active"))
                .thenReturn(List.of(target));
        when(repository.findById("r1")).thenReturn(Optional.of(target));
        stubSemanticVerdict(L2SemanticMergeService.Action.MERGE, "r1");

        store.upsert("u1", "default",
                new L2ConflictMerger.Candidate("fact", "project.database", "项目数据库为 MySQL", 0.9, "部署评审", "active"),
                "msg-1", now);

        assertThat(target.getStatus()).isEqualTo("active");
        assertThat(target.getStateKey()).isEqualTo("project.database");
        assertThat(target.getStateValue()).isEqualTo("项目数据库为 MySQL（归一）");
        assertThat(target.getConfidence()).isEqualTo(0.9);
        assertThat(target.getSourceMsgId()).isEqualTo("msg-1");
        verify(repository, times(1)).save(target);
    }

    @Test
    void upsert_update_supersedesTargetAndInsertsNewActive() {
        UserContextStateEntity target = row("r1", "fact", "diet.spicy", "用户不吃辣");
        when(repository.findByUserIdAndTenantIdAndKindAndStateKeyAndStatus(
                "u1", "default", "fact", "diet.spice_level", "active"))
                .thenReturn(Optional.empty());
        when(repository.findByUserIdAndTenantIdAndStatus("u1", "default", "active"))
                .thenReturn(List.of(target));
        when(repository.findById("r1")).thenReturn(Optional.of(target));
        stubSemanticVerdict(L2SemanticMergeService.Action.UPDATE, "r1");

        store.upsert("u1", "default",
                new L2ConflictMerger.Candidate("fact", "diet.spice_level", "用户改为偏好重辣", 0.9),
                "msg-1", now);

        assertThat(target.getStatus()).isEqualTo("superseded");
        ArgumentCaptor<UserContextStateEntity> cap = ArgumentCaptor.forClass(UserContextStateEntity.class);
        verify(repository, times(2)).save(cap.capture());
        List<UserContextStateEntity> saved = cap.getAllValues();
        assertThat(saved.get(1).getStatus()).isEqualTo("active");
        assertThat(saved.get(1).getStateKey()).isEqualTo("diet.spice_level");
        assertThat(saved.get(1).getStateValue()).isEqualTo("用户改为偏好重辣");
    }

    @Test
    void upsert_conflict_marksBothConflict() {
        UserContextStateEntity target = row("r1", "fact", "diet.spicy", "用户不吃辣");
        when(repository.findByUserIdAndTenantIdAndKindAndStateKeyAndStatus(
                "u1", "default", "fact", "diet.spicy2", "active"))
                .thenReturn(Optional.empty());
        when(repository.findByUserIdAndTenantIdAndStatus("u1", "default", "active"))
                .thenReturn(List.of(target));
        when(repository.findById("r1")).thenReturn(Optional.of(target));
        stubSemanticVerdict(L2SemanticMergeService.Action.CONFLICT, "r1");

        store.upsert("u1", "default",
                new L2ConflictMerger.Candidate("fact", "diet.spicy2", "用户偏好重辣", 0.9),
                "msg-1", now);

        assertThat(target.getStatus()).isEqualTo("conflict");
        ArgumentCaptor<UserContextStateEntity> cap = ArgumentCaptor.forClass(UserContextStateEntity.class);
        verify(repository, times(2)).save(cap.capture());
        assertThat(cap.getAllValues().get(1).getStatus()).isEqualTo("conflict");
        assertThat(cap.getAllValues().get(1).getStateValue()).isEqualTo("用户偏好重辣");
    }

    @Test
    void upsert_semanticDisabled_skipsJudgeAndInsertsDirectly() {
        properties.getL2().setSemanticMergeEnabled(false);
        when(repository.findByUserIdAndTenantIdAndKindAndStateKeyAndStatus(
                "u1", "default", "fact", "project.database", "active"))
                .thenReturn(Optional.empty());

        store.upsert("u1", "default",
                new L2ConflictMerger.Candidate("fact", "project.database", "项目数据库为 MySQL", 0.9),
                "msg-1", now);

        verify(repository, never()).findByUserIdAndTenantIdAndStatus(any(), any(), any());
        ArgumentCaptor<UserContextStateEntity> cap = ArgumentCaptor.forClass(UserContextStateEntity.class);
        verify(repository).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo("active");
    }

    @Test
    void upsert_taskPrefixKey_skipsSemanticJudge() {
        when(repository.findByUserIdAndTenantIdAndKindAndStateKeyAndStatus(
                "u1", "default", "todo", "task.a1b2c3d4.t1", "active"))
                .thenReturn(Optional.empty());

        store.upsert("u1", "default",
                new L2ConflictMerger.Candidate("todo", "task.a1b2c3d4.t1", "部署 QA 环境", 1.0, "目标", "active"),
                "msg-1", now);

        verify(repository, never()).findByUserIdAndTenantIdAndStatus(any(), any(), any());
        ArgumentCaptor<UserContextStateEntity> cap = ArgumentCaptor.forClass(UserContextStateEntity.class);
        verify(repository).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo("active");
    }

    @Test
    void upsert_mergeTargetNoLongerActive_fallsBackToInsert() {
        UserContextStateEntity gone = row("r1", "fact", "project.db", "项目存储用 MySQL");
        gone.setStatus("superseded");
        when(repository.findByUserIdAndTenantIdAndKindAndStateKeyAndStatus(
                "u1", "default", "fact", "project.database", "active"))
                .thenReturn(Optional.empty());
        when(repository.findByUserIdAndTenantIdAndStatus("u1", "default", "active"))
                .thenReturn(List.of(row("r2", "fact", "project.db", "项目存储用 MySQL")));
        when(repository.findById("r1")).thenReturn(Optional.of(gone));
        stubSemanticVerdict(L2SemanticMergeService.Action.MERGE, "r1");

        store.upsert("u1", "default",
                new L2ConflictMerger.Candidate("fact", "project.database", "项目数据库为 MySQL", 0.9),
                "msg-1", now);

        ArgumentCaptor<UserContextStateEntity> cap = ArgumentCaptor.forClass(UserContextStateEntity.class);
        verify(repository, times(1)).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo("active");
        assertThat(cap.getValue().getStateKey()).isEqualTo("project.database");
    }

    @Test
    void upsert_workspaceScope_semanticUsesWorkspaceQuery() {
        UserContextStateEntity target = row("r1", "fact", "project.db", "项目存储用 MySQL");
        target.setUserId("");
        target.setWorkspaceId("ws-1");
        when(repository.findByWorkspaceIdAndTenantIdAndKindAndStateKeyAndStatus(
                "ws-1", "default", "fact", "project.database", "active"))
                .thenReturn(Optional.empty());
        when(repository.findByWorkspaceIdAndTenantIdAndStatus("ws-1", "default", "active"))
                .thenReturn(List.of(target));
        when(repository.findById("r1")).thenReturn(Optional.of(target));
        stubSemanticVerdict(L2SemanticMergeService.Action.MERGE, "r1");

        store.upsertWorkspace("ws-1", "default",
                new L2ConflictMerger.Candidate("fact", "project.database", "项目数据库为 MySQL", 0.9),
                "msg-1", now);

        verify(repository, never()).findByUserIdAndTenantIdAndStatus(any(), any(), any());
        assertThat(target.getStateKey()).isEqualTo("project.database");
    }
}
