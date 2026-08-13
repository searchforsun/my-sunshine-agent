package com.sunshine.orchestrator.execution;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.exception.OrchestratorErrorCode;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ResourceDispatcher 语义：ExecutionDispatcher 三模式钉死分发（routing v6 / H-5）。
 */
@ExtendWith(MockitoExtension.class)
class ResourceDispatcherTest {

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
    void pro_dispatchesToHarness_whenEnabled() {
        executionProperties.getHarness().setEnabled(true);
        when(plannerHarnessExecutor.execute(any())).thenReturn(Flux.just(StreamToken.content("harness")));

        List<StreamToken> tokens = dispatcher.execute(ctx(ExecutionMode.PRO)).collectList().block();

        assertThat(tokens).extracting(StreamToken::text).containsExactly("harness");
        verify(plannerHarnessExecutor).execute(any());
        verifyNoInteractions(planWorkflowExecutor, reactExecutor, workflowExecutor);
    }

    @Test
    void pro_failsExplicitly_whenHarnessDisabled() {
        executionProperties.getHarness().setEnabled(false);

        assertThatThrownBy(() -> dispatcher.execute(ctx(ExecutionMode.PRO)).collectList().block())
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(OrchestratorErrorCode.HARNESS_DISABLED);

        verifyNoInteractions(plannerHarnessExecutor, planWorkflowExecutor, reactExecutor, workflowExecutor);
    }

    @Test
    void fast_dispatchesToReact() {
        when(reactExecutor.execute(any())).thenReturn(Flux.just(StreamToken.content("react")));

        List<StreamToken> tokens = dispatcher.execute(ctx(ExecutionMode.FAST)).collectList().block();

        assertThat(tokens).extracting(StreamToken::text).containsExactly("react");
        verify(reactExecutor).execute(any());
        verifyNoInteractions(plannerHarnessExecutor, planWorkflowExecutor, workflowExecutor);
    }

    @Test
    void workflow_dispatchesToWorkflowExecutor() {
        when(workflowExecutor.execute(any())).thenReturn(Flux.just(StreamToken.content("workflow")));

        List<StreamToken> tokens = dispatcher.execute(ctx(ExecutionMode.WORKFLOW)).collectList().block();

        assertThat(tokens).extracting(StreamToken::text).containsExactly("workflow");
        verify(workflowExecutor).execute(any());
        verifyNoInteractions(plannerHarnessExecutor, planWorkflowExecutor, reactExecutor);
    }

    @Test
    void neverCallsPlanWorkflowExecutor_onProOrFast() {
        executionProperties.getHarness().setEnabled(true);
        when(plannerHarnessExecutor.execute(any())).thenReturn(Flux.just(StreamToken.content("harness")));
        when(reactExecutor.execute(any())).thenReturn(Flux.just(StreamToken.content("react")));

        dispatcher.execute(ctx(ExecutionMode.PRO)).collectList().block();
        dispatcher.execute(ctx(ExecutionMode.FAST)).collectList().block();

        verifyNoInteractions(planWorkflowExecutor);
    }

    private static ExecutionStreamContext ctx(ExecutionMode mode) {
        return new ExecutionStreamContext(
                "conv-1",
                "msg-1",
                "query",
                AssembledContext.empty(),
                null,
                null,
                "u1",
                "default",
                new ExecutionPlan(mode, null, Map.of(), "test"));
    }
}
