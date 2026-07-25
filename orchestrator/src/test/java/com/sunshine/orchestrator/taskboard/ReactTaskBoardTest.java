package com.sunshine.orchestrator.taskboard;

import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.TimelineStepId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

/** 原生 TaskList 终态收口 + 进度文案 */
@ExtendWith(MockitoExtension.class)
class ReactTaskBoardTest {

    @Mock
    private TaskBoardTimelineSupport timelineSupport;
    @Mock
    private ReactTaskBoardAuditService auditService;

    private ReactTaskBoardService service;

    @BeforeEach
    void setUp() {
        service = new ReactTaskBoardService(timelineSupport, auditService);
    }

    @Test
    void progressSummary_countsCompletedOnly() {
        List<TaskBoardItemView> items = List.of(
                new TaskBoardItemView("t1", "a", "completed"),
                new TaskBoardItemView("t2", "b", "in_progress"),
                new TaskBoardItemView("t3", "c", "pending"));
        assertThat(ReactTaskBoardService.progressSummary(items)).isEqualTo("1/3 已完成");
        assertThat(ReactTaskBoardService.allTerminal(items)).isFalse();
    }

    @Test
    void allTerminal_completedOrCancelled() {
        assertThat(ReactTaskBoardService.allTerminal(List.of(
                new TaskBoardItemView("t1", "a", "completed"),
                new TaskBoardItemView("t2", "b", "cancelled")))).isTrue();
    }

    @Test
    void findActiveTask_returnsInProgressContent() {
        assertThat(ReactTaskBoardService.findActiveTask(List.of(
                new TaskBoardItemView("t1", "a", "completed"),
                new TaskBoardItemView("t2", "检索中", "in_progress")))).isEqualTo("检索中");
        assertThat(ReactTaskBoardService.findActiveTask(List.of(
                new TaskBoardItemView("t1", "a", "completed")))).isEmpty();
    }

    @Test
    void finalizeNativeTimeline_readsTasksContext_persistsAuditAndCompletes() {
        ProcessingTimelineSession session = org.mockito.Mockito.mock(ProcessingTimelineSession.class);
        when(session.hasStep(TimelineStepId.TASKS.id())).thenReturn(true);
        com.sunshine.orchestrator.agent.runtime.AgentRunRequest request =
                org.mockito.Mockito.mock(com.sunshine.orchestrator.agent.runtime.AgentRunRequest.class);
        when(request.assistantMessageId()).thenReturn("msg-native");
        io.agentscope.core.state.AgentState agentState = io.agentscope.core.state.AgentState.builder()
                .tasksContext(new io.agentscope.core.state.TaskContextState(List.of(
                        io.agentscope.core.state.Task.builder().id("t1").subject("检索").description("检索")
                                .state(io.agentscope.core.state.Task.State.COMPLETED).build())))
                .build();

        service.finalizeNativeTimeline(session, request, agentState);

        List<TaskBoardItemView> expected = List.of(new TaskBoardItemView("t1", "检索", "completed"));
        ArgumentCaptor<ReactTaskBoardState> auditCap = ArgumentCaptor.forClass(ReactTaskBoardState.class);
        verify(auditService).persistFinal(auditCap.capture());
        assertThat(auditCap.getValue().assistantMsgId()).isEqualTo("msg-native");
        assertThat(auditCap.getValue().items()).isEqualTo(expected);
        verify(timelineSupport).completeOnRunEnd(session, expected, 1, "1/1 已完成");
        verify(timelineSupport, never()).dismissEmptyPlaceholder(any());
    }

    @Test
    void finalizeNativeTimeline_emptyTasksContext_dismissesPlaceholder() {
        ProcessingTimelineSession session = org.mockito.Mockito.mock(ProcessingTimelineSession.class);
        when(session.hasStep(TimelineStepId.TASKS.id())).thenReturn(true);
        com.sunshine.orchestrator.agent.runtime.AgentRunRequest request =
                org.mockito.Mockito.mock(com.sunshine.orchestrator.agent.runtime.AgentRunRequest.class);
        when(request.assistantMessageId()).thenReturn("msg-empty-native");
        io.agentscope.core.state.AgentState agentState = io.agentscope.core.state.AgentState.builder()
                .tasksContext(new io.agentscope.core.state.TaskContextState(List.of()))
                .build();

        service.finalizeNativeTimeline(session, request, agentState);

        verify(timelineSupport).dismissEmptyPlaceholder(session);
        verify(auditService, never()).persistFinal(any());
        verify(timelineSupport, never()).completeOnRunEnd(any(), any(), any(Integer.class), any());
    }

    @Test
    void finalizeNativeTimeline_nullAgentState_dismissesPlaceholder() {
        ProcessingTimelineSession session = org.mockito.Mockito.mock(ProcessingTimelineSession.class);
        when(session.hasStep(TimelineStepId.TASKS.id())).thenReturn(true);
        com.sunshine.orchestrator.agent.runtime.AgentRunRequest request =
                org.mockito.Mockito.mock(com.sunshine.orchestrator.agent.runtime.AgentRunRequest.class);
        when(request.assistantMessageId()).thenReturn("msg-null");

        service.finalizeNativeTimeline(session, request, null);

        verify(timelineSupport).dismissEmptyPlaceholder(session);
        verify(auditService, never()).persistFinal(any());
    }
}
