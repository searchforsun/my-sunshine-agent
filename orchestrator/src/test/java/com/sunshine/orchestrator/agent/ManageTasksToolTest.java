package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.taskboard.ReactTaskBoardApplyResult;
import com.sunshine.orchestrator.taskboard.ReactTaskBoardService;
import com.sunshine.orchestrator.taskboard.TaskBoardItemView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManageTasksToolTest {

    private static final String BRIDGE = "main-bridge";
    private static final String MSG = "msg-1";

    @Mock
    private ReactTaskBoardService taskBoardService;

    private ManageTasksTool tool;
    private StepEventBridgeRegistry registry;

    @BeforeEach
    void setUp() {
        tool = new ManageTasksTool(taskBoardService);
        registry = new StepEventBridgeRegistry();
        StepEventBridge.bindRegistry(registry);
    }

    @AfterEach
    void tearDown() {
        registry.clearAll();
    }

    @Test
    void manageTasks_success_emitsTimelineUpdate() {
        List<TaskBoardItemView> items = List.of(
                new TaskBoardItemView("t1", "检索制度", "in_progress"),
                new TaskBoardItemView("t2", "汇总结论", "pending"));
        ReactTaskBoardApplyResult applyResult = ReactTaskBoardApplyResult.success(1, "0/2 已完成", items);
        when(taskBoardService.apply(eq(MSG), eq(false), any(), eq(List.of()))).thenReturn(applyResult);

        ProcessingTimelineSession session = new ProcessingTimelineSession();
        registry.bind(BRIDGE, session);
        StepEventBridge.bindHitlBridge(BRIDGE, MSG, true);
        StepEventBridge.registerMainRun(MSG, BRIDGE);

        String json = tool.manageTasks(false, """
                [
                  {"content":"检索制度","status":"in_progress"},
                  {"content":"汇总结论","status":"pending"}
                ]
                """);

        assertThat(json).contains("\"ok\":true");
        assertThat(json).contains("\"revision\":1");

        ArgumentCaptor<ReactTaskBoardApplyResult> captor = ArgumentCaptor.forClass(ReactTaskBoardApplyResult.class);
        verify(taskBoardService).emitTimelineUpdate(eq(session), captor.capture());
        assertThat(captor.getValue().items()).hasSize(2);
    }

    @Test
    void manageTasks_rejectsForbiddenFields() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        registry.bind(BRIDGE, session);
        StepEventBridge.bindHitlBridge(BRIDGE, MSG, true);

        String json = tool.manageTasks(false, """
                [{"content":"步骤","status":"pending","edges":[]}]
                """);
        assertThat(json).contains("\"ok\":false");
        assertThat(json).contains("edges");
    }
}
