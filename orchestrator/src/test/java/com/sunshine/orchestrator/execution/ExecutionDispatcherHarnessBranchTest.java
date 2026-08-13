package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.plan.harness.PlannerHarnessExecutor;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExecutionDispatcherHarnessBranchTest {

    @Mock
    private WorkflowExecutor workflowExecutor;
    @Mock
    private ReactExecutor reactExecutor;
    @Mock
    private PlanWorkflowExecutor planWorkflowExecutor;
    @Mock
    private PlannerHarnessExecutor plannerHarnessExecutor;

    private AgentExecutionProperties executionProperties;
    private ExecutionDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        executionProperties = new AgentExecutionProperties();
        dispatcher = new ExecutionDispatcher(
                workflowExecutor,
                reactExecutor,
                planWorkflowExecutor,
                plannerHarnessExecutor,
                executionProperties);
    }

    @Test
    void planWorkflow_whenHarnessDisabled_delegatesToPlanWorkflowExecutor() {
        executionProperties.getHarness().setEnabled(false);
        when(planWorkflowExecutor.execute(any())).thenReturn(Flux.just(StreamToken.content("plan-workflow")));

        List<StreamToken> tokens = dispatcher.execute(planWorkflowCtx()).collectList().block();

        assertThat(tokens).extracting(StreamToken::text).containsExactly("plan-workflow");
        verify(planWorkflowExecutor).execute(any());
        verifyNoInteractions(plannerHarnessExecutor);
    }

    @Test
    void planWorkflow_whenHarnessEnabled_delegatesToPlannerHarnessExecutor() {
        executionProperties.getHarness().setEnabled(true);
        when(plannerHarnessExecutor.execute(any())).thenReturn(Flux.just(StreamToken.content("harness")));

        List<StreamToken> tokens = dispatcher.execute(planWorkflowCtx()).collectList().block();

        assertThat(tokens).extracting(StreamToken::text).containsExactly("harness");
        verify(plannerHarnessExecutor).execute(any());
        verifyNoInteractions(planWorkflowExecutor);
    }

    private static ExecutionStreamContext planWorkflowCtx() {
        return new ExecutionStreamContext(
                "conv-1",
                "msg-1",
                "分两步规划",
                AssembledContext.empty(),
                null,
                null,
                "u1",
                "default",
                new ExecutionPlan(ExecutionMode.PRO, null, Map.of(), "test"));
    }
}
