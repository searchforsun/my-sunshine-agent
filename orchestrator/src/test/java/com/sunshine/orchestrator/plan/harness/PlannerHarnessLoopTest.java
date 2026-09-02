package com.sunshine.orchestrator.plan.harness;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlannerHarnessLoopTest {

    @Mock
    private HarnessPlanner planner;
    @Mock
    private PlanNotebookStore store;

    private AgentExecutionProperties executionProperties;
    private PlannerHarnessLoop loop;

    @BeforeEach
    void setUp() {
        executionProperties = new AgentExecutionProperties();
        executionProperties.getHarness().setEnabled(true);
        executionProperties.getHarness().setMaxDurationMs(60_000);
        loop = new PlannerHarnessLoop(planner, store, executionProperties);
    }

    @Test
    void run_delegatesToPlannerAndPersistsOnTerminate() {
        PlanNotebook notebook = PlanNotebook.create("goal", "q", "task", 12, 24);
        notebook.setSessionId("sess-delegate");
        when(planner.runPlanned(any(), any()))
                .thenReturn(Flux.just(
                        StreamToken.content("Planner 正文 token1"),
                        StreamToken.content(" Planner 正文 token2")));

        List<StreamToken> tokens = loop.run(streamCtx(), notebook).collectList().block();

        assertThat(tokens).hasSize(2);
        verify(planner, times(1)).runPlanned(any(), any());
        verify(store, times(1)).save(notebook);
    }

    @Test
    void run_propagatesPlannerErrorsAndStillPersists() {
        PlanNotebook notebook = PlanNotebook.create("goal", "q", "task", 12, 24);
        notebook.setSessionId("sess-error");
        when(planner.runPlanned(any(), any()))
                .thenReturn(Flux.error(new RuntimeException("Planner LLM 异常")));

        Throwable caught = null;
        try {
            loop.run(streamCtx(), notebook).collectList().block();
        } catch (Throwable t) {
            caught = t;
        }

        assertThat(caught).isNotNull();
        assertThat(caught.getMessage()).contains("Planner LLM 异常");
        // 异常路径也落盘已有结果
        verify(store, times(1)).save(notebook);
    }

    private static ExecutionStreamContext streamCtx() {
        return new ExecutionStreamContext(
                "conv-1", "msg-1", "执行 planner run", null,
                null, null, "user-1", "tenant-1", null);
    }
}
