package com.sunshine.orchestrator.agent;

import io.agentscope.core.ReActAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SpawnRunRegistryTest {

    @Mock
    private ReActAgent agent;

    @Test
    void cancel_marksCancelledAndInterruptsBoundAgent() {
        SpawnRunRegistry registry = new SpawnRunRegistry();
        SpawnSubagentTimelineBridge bridge = new SpawnSubagentTimelineBridge("run-1", "子任务", "p");
        registry.register("run-1", "msg-1", "p", "main-bridge", bridge);
        registry.bindAgent("run-1", agent);

        assertThat(registry.cancel("run-1")).isTrue();
        assertThat(registry.isCancelled("run-1")).isTrue();
        verify(agent).interrupt();
    }

    @Test
    void cancel_unknownRunId_returnsFalse() {
        SpawnRunRegistry registry = new SpawnRunRegistry();
        assertThat(registry.cancel("missing")).isFalse();
    }

    @Test
    void bindAgent_afterCancel_interruptsImmediately() {
        SpawnRunRegistry registry = new SpawnRunRegistry();
        SpawnSubagentTimelineBridge bridge = new SpawnSubagentTimelineBridge("run-2", "子任务", "p");
        registry.register("run-2", "msg-1", "p", "main-bridge", bridge);
        assertThat(registry.cancel("run-2")).isTrue();

        registry.bindAgent("run-2", agent);
        verify(agent).interrupt();
    }

    @Test
    void unregister_removesHandle() {
        SpawnRunRegistry registry = new SpawnRunRegistry();
        SpawnSubagentTimelineBridge bridge = new SpawnSubagentTimelineBridge("run-3", "子任务", "p");
        registry.register("run-3", "msg-1", "p", "main-bridge", bridge);
        registry.unregister("run-3");
        assertThat(registry.get("run-3")).isNull();
        assertThat(registry.cancel("run-3")).isFalse();
    }
}
