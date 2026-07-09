package com.sunshine.orchestrator.taskboard;

import com.sunshine.orchestrator.config.AgentExecutionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReactTaskBoardTest {

    @Mock
    private ReactTaskBoardStore store;
    @Mock
    private TaskBoardTimelineSupport timelineSupport;
    @Mock
    private ReactTaskBoardAuditService auditService;

    private ReactTaskBoardService service;

    @BeforeEach
    void setUp() {
        AgentExecutionProperties props = new AgentExecutionProperties();
        props.getReact().getTaskboard().setMaxItems(12);
        service = new ReactTaskBoardService(store, props, timelineSupport, auditService);
    }

    @Test
    void apply_replace_createsBoardWithRevisionOne() {
        when(store.load("msg-1")).thenReturn(Optional.empty());

        ReactTaskBoardApplyResult result = service.apply(
                "msg-1",
                false,
                List.of(
                        new TaskBoardItemInput(null, "检索制度", "in_progress"),
                        new TaskBoardItemInput(null, "汇总结论", "pending")),
                List.of());

        assertThat(result.ok()).isTrue();
        assertThat(result.revision()).isEqualTo(1);
        assertThat(result.summary()).isEqualTo("0/2 已完成");
        assertThat(result.items()).extracting(TaskBoardItemView::id).containsExactly("t1", "t2");
        assertThat(result.items()).extracting(TaskBoardItemView::status)
                .containsExactly("in_progress", "pending");

        ArgumentCaptor<ReactTaskBoardState> captor = ArgumentCaptor.forClass(ReactTaskBoardState.class);
        verify(store).save(captor.capture());
        assertThat(captor.getValue().assistantMsgId()).isEqualTo("msg-1");
    }

    @Test
    void apply_merge_updatesExistingItem() {
        ReactTaskBoardState existing = new ReactTaskBoardState(
                "board-1", "msg-1", 2, 1000L,
                List.of(
                        new TaskBoardItemView("t1", "检索制度", "in_progress"),
                        new TaskBoardItemView("t2", "汇总结论", "pending")));
        when(store.load("msg-1")).thenReturn(Optional.of(existing));

        ReactTaskBoardApplyResult result = service.apply(
                "msg-1",
                true,
                List.of(new TaskBoardItemInput("t1", "检索制度", "completed")),
                List.of());

        assertThat(result.ok()).isTrue();
        assertThat(result.revision()).isEqualTo(3);
        assertThat(result.summary()).isEqualTo("1/2 已完成");
        assertThat(result.items().get(0).status()).isEqualTo("completed");
        assertThat(result.items().get(1).content()).isEqualTo("汇总结论");
    }

    @Test
    void apply_rejectsForbiddenDagFields() {
        ReactTaskBoardApplyResult result = service.apply(
                "msg-1",
                false,
                List.of(new TaskBoardItemInput("t1", "步骤1", "pending")),
                List.of("edges", "tool"));

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("edges");
    }

    @Test
    void apply_enforcesSingleInProgress() {
        when(store.load(any())).thenReturn(Optional.empty());

        ReactTaskBoardApplyResult result = service.apply(
                "msg-1",
                false,
                List.of(
                        new TaskBoardItemInput("t1", "步骤1", "in_progress"),
                        new TaskBoardItemInput("t2", "步骤2", "in_progress")),
                List.of());

        assertThat(result.ok()).isTrue();
        assertThat(result.items()).extracting(TaskBoardItemView::status)
                .containsExactly("in_progress", "pending");
    }

    @Test
    void apply_merge_matchesByContentWhenIdMissing() {
        ReactTaskBoardState existing = new ReactTaskBoardState(
                "board-1", "msg-1", 1, 1000L,
                List.of(
                        new TaskBoardItemView("t1", "查询待审批报销单据", "in_progress"),
                        new TaskBoardItemView("t2", "查询公司报销制度与合规要求", "pending"),
                        new TaskBoardItemView("t3", "逐笔分析报销合规性", "pending"),
                        new TaskBoardItemView("t4", "给出能否提交审批的结论与建议", "pending")));
        when(store.load("msg-1")).thenReturn(Optional.of(existing));

        ReactTaskBoardApplyResult result = service.apply(
                "msg-1",
                true,
                List.of(
                        new TaskBoardItemInput(null, "查询待审批报销单据", "completed"),
                        new TaskBoardItemInput(null, "查询公司报销制度与合规要求", "completed"),
                        new TaskBoardItemInput(null, "逐笔分析报销合规性", "completed"),
                        new TaskBoardItemInput(null, "给出能否提交审批的结论与建议", "completed")),
                List.of());

        assertThat(result.ok()).isTrue();
        assertThat(result.items()).hasSize(4);
        assertThat(result.items()).extracting(TaskBoardItemView::status)
                .containsExactly("completed", "completed", "completed", "completed");
        assertThat(result.summary()).isEqualTo("4/4 已完成");
    }

    @Test
    void progressSummary_countsCompletedOnly() {
        List<TaskBoardItemView> items = List.of(
                new TaskBoardItemView("t1", "a", "completed"),
                new TaskBoardItemView("t2", "b", "cancelled"),
                new TaskBoardItemView("t3", "c", "pending"));
        assertThat(ReactTaskBoardService.progressSummary(items)).isEqualTo("1/3 已完成");
        assertThat(ReactTaskBoardService.allTerminal(items)).isFalse();
    }
}
