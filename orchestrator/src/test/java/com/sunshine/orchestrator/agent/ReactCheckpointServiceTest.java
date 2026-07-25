package com.sunshine.orchestrator.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReactCheckpointServiceTest {

    @Mock
    private AgentStateStore stateStore;
    @Mock
    private HarnessAgent agent;
    @Mock
    private ReActAgent delegate;

    @Test
    void hasCheckpoint_delegatesToStateStore() {
        when(stateStore.exists("u-1", "msg-1")).thenReturn(true);
        ReactCheckpointService svc = new ReactCheckpointService(stateStore);
        assertThat(svc.hasCheckpoint("u-1", "msg-1")).isTrue();
    }

    @Test
    void hasCheckpoint_falseWhenNotExists() {
        when(stateStore.exists("u-1", "msg-1")).thenReturn(false);
        ReactCheckpointService svc = new ReactCheckpointService(stateStore);
        assertThat(svc.hasCheckpoint("u-1", "msg-1")).isFalse();
    }

    @Test
    void interrupt_callsAgentInterruptAndSavesCheckpoint() {
        when(agent.getDelegate()).thenReturn(delegate);
        ReactCheckpointService svc = new ReactCheckpointService(stateStore);
        svc.interrupt(agent, "u-1", "msg-1");
        verify(agent).interrupt();
        verify(delegate).saveAgentState(eq("u-1"), eq("msg-1"));
    }

    @Test
    void resumeCtx_buildsRuntimeContextWithUserIdAndSessionId() {
        ReactCheckpointService svc = new ReactCheckpointService(stateStore);
        RuntimeContext ctx = svc.resumeCtx("u-1", "msg-1");
        assertThat(ctx.getUserId()).isEqualTo("u-1");
        assertThat(ctx.getSessionId()).isEqualTo("msg-1");
    }

    @Test
    void deleteCheckpoint_delegatesToStateStore() {
        ReactCheckpointService svc = new ReactCheckpointService(stateStore);
        svc.deleteCheckpoint("u-1", "msg-1");
        verify(stateStore).delete(eq("u-1"), eq("msg-1"));
    }
}
