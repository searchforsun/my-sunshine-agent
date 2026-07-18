package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.processing.TimelineLabelJUnitExtension;
import io.agentscope.core.ReActAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith({MockitoExtension.class, TimelineLabelJUnitExtension.class})
class SpawnRunRegistryTest {

    @Mock
    private ReActAgent agent;

    @Test
    void cancel_marksCancelledAndInterruptsBoundAgent() {
        SpawnRunRegistry registry = SpawnRunRegistry.forTest();
        SpawnSubagentTimelineBridge bridge = new SpawnSubagentTimelineBridge("run-1", "子任务", "p");
        registry.register("run-1", "msg-1", "p", "main-bridge", bridge);
        registry.bindAgent("run-1", agent);

        assertThat(registry.cancel("run-1")).isTrue();
        assertThat(registry.isCancelled("run-1")).isTrue();
        verify(agent).interrupt();
    }

    @Test
    void cancel_unknownRunId_returnsFalse() {
        SpawnRunRegistry registry = SpawnRunRegistry.forTest();
        assertThat(registry.cancel("missing")).isFalse();
    }

    @Test
    void bindAgent_afterCancel_interruptsImmediately() {
        SpawnRunRegistry registry = SpawnRunRegistry.forTest();
        SpawnSubagentTimelineBridge bridge = new SpawnSubagentTimelineBridge("run-2", "子任务", "p");
        registry.register("run-2", "msg-1", "p", "main-bridge", bridge);
        assertThat(registry.cancel("run-2")).isTrue();

        registry.bindAgent("run-2", agent);
        verify(agent).interrupt();
    }

    @Test
    void unregister_removesHandle() {
        SpawnRunRegistry registry = SpawnRunRegistry.forTest();
        SpawnSubagentTimelineBridge bridge = new SpawnSubagentTimelineBridge("run-3", "子任务", "p");
        registry.register("run-3", "msg-1", "p", "main-bridge", bridge);
        registry.unregister("run-3");
        assertThat(registry.get("run-3")).isNull();
        assertThat(registry.cancel("run-3")).isFalse();
    }

    @Test
    void register_blankMessageId_skipped() {
        SpawnRunRegistry registry = SpawnRunRegistry.forTest();
        SpawnSubagentTimelineBridge bridge = new SpawnSubagentTimelineBridge("run-x", "子任务", "p");
        registry.register("run-x", null, "p", "main-bridge", bridge);
        assertThat(registry.get("run-x")).isNull();
    }
}
