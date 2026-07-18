package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.client.StreamToken;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpawnSubagentTimelineBridgeTest {

    @Test
    void wrapStepDeltaTwice_appendsReasoningTwice() {
        SpawnSubagentTimelineBridge bridge = new SpawnSubagentTimelineBridge("r1", "子任务", "prompt");
        bridge.wrap(StreamToken.step(ProcessingStep.running("think", "think", "规划推理")));
        bridge.wrap(StreamToken.stepDelta("think", "reasoning", "用户"));
        bridge.wrap(StreamToken.stepDelta("think", "reasoning", "用户"));

        assertThat(bridge.subSteps()).hasSize(1);
        assertThat(bridge.subSteps().get(0).reasoning()).isEqualTo("用户用户");
    }

    @Test
    void wrapStepDeltaOnce_keepsReasoningIntact() {
        SpawnSubagentTimelineBridge bridge = new SpawnSubagentTimelineBridge("r2", "子任务", "prompt");
        bridge.wrap(StreamToken.step(ProcessingStep.running("think", "think", "规划推理")));
        bridge.wrap(StreamToken.stepDelta("think", "reasoning", "用户要求"));

        assertThat(bridge.subSteps().get(0).reasoning()).isEqualTo("用户要求");
    }

    @Test
    void wrapContent_emitsParentResultStepDelta() {
        SpawnSubagentTimelineBridge bridge = new SpawnSubagentTimelineBridge("r3", "子任务", "prompt");
        List<StreamToken> out = bridge.wrap(StreamToken.content("报销"));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).isStepDelta()).isTrue();
        assertThat(out.get(0).stepId()).isEqualTo("subagent-r3");
        assertThat(out.get(0).channel()).isEqualTo("result");
        assertThat(out.get(0).text()).isEqualTo("报销");
    }

    @Test
    void wrapContentLifecycle_scopesToParentLikeWorkflowAgent() {
        SpawnSubagentTimelineBridge bridge = new SpawnSubagentTimelineBridge("r4", "子任务", "prompt");
        // 生产段 id 形如 content-{n}（见 ContentSegmentCoordinator）
        List<StreamToken> start = bridge.wrap(StreamToken.contentStart("content-1", "think"));
        assertThat(start).hasSize(1);
        assertThat(start.get(0).isContentStart()).isTrue();
        assertThat(start.get(0).scopeNodeStepId()).isEqualTo("subagent-r4");

        List<StreamToken> chunk = bridge.wrap(StreamToken.contentInSegment("content-1", "要点"));
        assertThat(chunk).hasSize(1);
        assertThat(chunk.get(0).isContent()).isTrue();
        assertThat(chunk.get(0).scopeNodeStepId()).isEqualTo("subagent-r4");
        assertThat(chunk.get(0).text()).isEqualTo("要点");
    }

    @Test
    void cancel_emitsPausedTerminalStep() {
        SpawnSubagentTimelineBridge bridge = new SpawnSubagentTimelineBridge("r5", "子任务", "prompt");
        List<StreamToken> out = bridge.cancel(SpawnSubagentLabels.afterCancel(), "用户已取消");
        assertThat(out).hasSize(1);
        ProcessingStep parent = out.get(0).step();
        assertThat(parent).isNotNull();
        assertThat(parent.lifecycle()).isEqualTo("paused");
        assertThat(parent.summary().after()).isEqualTo(SpawnSubagentLabels.afterCancel());
        assertThat(parent.result()).isEqualTo("用户已取消");
        assertThat(parent.endedAt()).isNotNull();
    }

    @Test
    void wrap_afterCancel_doesNotEmitParentRunning() {
        SpawnSubagentTimelineBridge bridge = new SpawnSubagentTimelineBridge("r6", "子任务", "prompt");
        bridge.markUserCancelled();
        ProcessingStep child = ProcessingStep.running("think", "think", "综合分析");
        List<StreamToken> out = bridge.wrap(StreamToken.step(child));
        assertThat(out).isEmpty();
        assertThat(bridge.subSteps()).hasSize(1);
    }
}
