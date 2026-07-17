package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.memory.MemoryContext;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExecutionDispatcherTest {

    @Mock
    private WorkflowExecutor workflowExecutor;
    @Mock
    private ReactExecutor reactExecutor;
    @Mock
    private PlanWorkflowExecutor planWorkflowExecutor;
    @Mock
    private ExpertConsultationExecutor expertConsultationExecutor;
    @InjectMocks
    private ExecutionDispatcher dispatcher;

    @Test
    void dispatchesPeerCollabToExpertExecutor() {
        ExecutionStreamContext ctx = new ExecutionStreamContext(
                "c1", "m1", "q", MemoryContext.empty(), "", "", "u", "t",
                new ExecutionPlan(ExecutionMode.PEER_COLLAB, null, java.util.Map.of(), "test"));
        when(expertConsultationExecutor.execute(any())).thenReturn(Flux.just(StreamToken.content("ok")));

        List<StreamToken> tokens = dispatcher.execute(ctx).collectList().block();
        assertThat(tokens).hasSize(1);
        assertThat(tokens.get(0).text()).isEqualTo("ok");

        verify(expertConsultationExecutor).execute(ctx);
    }
}
