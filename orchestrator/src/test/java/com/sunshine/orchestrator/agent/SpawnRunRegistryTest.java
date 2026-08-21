package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.plan.harness.WorkerTimelineBridge;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.TimelineLabelJUnitExtension;
import io.agentscope.core.ReActAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

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

    @Test
    void cancel_insideWorker_foldsTerminalIntoWorkerSubSteps() {
        // Worker 内 spawn：取消终态经 worker 桥折叠进 worker-{taskId}.subSteps（抽屉内卡片 paused），
        // 而非主时间线顶层（SpawnRunRegistry 无 GenerationRegistry 时仍不回落 Hook 队列）
        StepEventBridgeRegistry bridgeRegistry = new StepEventBridgeRegistry();
        StepEventBridge.bindRegistry(bridgeRegistry);
        try {
            String workerBridge = "worker-run-9";
            ProcessingTimelineSession workerSession = new ProcessingTimelineSession();
            bridgeRegistry.bind(workerBridge, workerSession, new ConcurrentLinkedQueue<>());
            StepEventBridge.bindHitlBridge(workerBridge, "msg-1", true);
            WorkerTimelineBridge workerTimeline = new WorkerTimelineBridge("t1", "任务一", "contract", "run-9");
            StepEventBridge.bindTokenWrapper(workerBridge, token -> {
                workerTimeline.wrap(token);
                return List.of();
            }, TokenWrapperMode.PASS_THROUGH);

            SpawnRunRegistry registry = SpawnRunRegistry.forTest();
            SpawnSubagentTimelineBridge subBridge = new SpawnSubagentTimelineBridge("sub-run-1", "子代理A", "p");
            registry.register("sub-run-1", "msg-1", "p", workerBridge, subBridge);

            assertThat(registry.cancel("sub-run-1")).isTrue();
            assertThat(workerTimeline.subSteps()).anyMatch(s ->
                    s.id() != null
                            && s.id().startsWith("subagent-")
                            && "paused".equals(s.lifecycle()));
        } finally {
            bridgeRegistry.clearAll();
            StepEventBridge.resetRegistry();
        }
    }
}
