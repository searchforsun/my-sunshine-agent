package com.sunshine.orchestrator.agent.runtime;

import com.sunshine.orchestrator.client.StreamToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlannerAgentRuntimeTest {

    @Mock
    private ReActAgentRuntime reactAgentRuntime;
    @InjectMocks
    private PlannerAgentRuntime runtime;

    @Test
    void run_delegatesToReActWithHarnessPrompt() {
        AgentRunRequest req = AgentRunRequest.planner("q", "u1", "default", "msg-p");
        when(reactAgentRuntime.runPlannerReAct(any())).thenReturn(Flux.just(StreamToken.content("{}")));

        runtime.run(req).collectList().block();

        ArgumentCaptor<AgentRunRequest> captor = ArgumentCaptor.forClass(AgentRunRequest.class);
        verify(reactAgentRuntime).runPlannerReAct(captor.capture());
        assertThat(captor.getValue().role()).isEqualTo(AgentRole.PLANNER);
        assertThat(captor.getValue().harnessPromptId()).isEqualTo(PlannerAgentRuntime.HARNESS_PROMPT_ID);
    }

    @Test
    void ensureHarnessPrompt_fillsBlankPromptId() {
        AgentRunRequest blank = new AgentRunRequest(
                AgentRole.PLANNER, "run-p", null, com.sunshine.orchestrator.context.AssembledContext.empty(),
                "q", java.util.List.of(), "u1", "default", "msg", null, null, null, 0,
                TimelineBinding.PLANNER_ONLY, false, null, null, 0, null, null, null, null, null, null);
        AgentRunRequest filled = PlannerAgentRuntime.ensureHarnessPrompt(blank);
        assertThat(filled.harnessPromptId()).isEqualTo("planner.harness");
    }
}
