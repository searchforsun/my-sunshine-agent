package com.sunshine.orchestrator.plan.harness;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.client.StreamToken;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerTimelineBridgeTest {

    @Test
    void parentStepId_prefixesTaskId() {
        assertThat(WorkerTimelineBridge.parentStepId("t1")).isEqualTo("worker-t1");
        assertThat(WorkerTimelineBridge.parentStepId("worker-t1")).isEqualTo("worker-t1");
        assertThat(WorkerTimelineBridge.parentStepId(null)).isEqualTo("worker-unknown");
    }

    @Test
    void begin_emitsRunningSkeleton() {
        WorkerTimelineBridge bridge = new WorkerTimelineBridge("t1", "调研仓库");

        List<StreamToken> tokens = bridge.begin();

        ProcessingStep step = stepOf(tokens);
        assertThat(step.id()).isEqualTo("worker-t1");
        assertThat(step.phase()).isEqualTo("worker");
        assertThat(step.lifecycle()).isEqualTo("running");
        assertThat(step.label()).isEqualTo("调研仓库");
        assertThat(step.summary()).isNotNull();
        assertThat(step.summary().active()).contains("调研仓库");
    }

    @Test
    void wrap_foldsInternalStepIntoSubSteps() {
        WorkerTimelineBridge bridge = new WorkerTimelineBridge("t1", "调研仓库");

        List<StreamToken> updated = bridge.wrap(
                StreamToken.step(ProcessingStep.running("think", "think", "思考过程")));

        assertThat(updated).hasSize(1);
        ProcessingStep parent = updated.get(0).step();
        assertThat(parent.id()).isEqualTo("worker-t1");
        assertThat(parent.lifecycle()).isEqualTo("running");
        assertThat(parent.subSteps()).isNotNull();
        assertThat(parent.subSteps()).extracting(ProcessingStep::id).containsExactly("think");
    }

    @Test
    void wrap_ignoresReasoning() {
        WorkerTimelineBridge bridge = new WorkerTimelineBridge("t1", "调研");

        List<StreamToken> tokens = bridge.wrap(StreamToken.reasoning("推理片段"));

        assertThat(tokens).isEmpty();
        assertThat(bridge.subSteps()).isEmpty();
    }

    @Test
    void complete_marksParentDoneWithResult() {
        WorkerTimelineBridge bridge = new WorkerTimelineBridge("t1", "调研仓库");

        List<StreamToken> tokens = bridge.complete("完成", "handoff 文本", true);

        ProcessingStep step = stepOf(tokens);
        assertThat(step.id()).isEqualTo("worker-t1");
        assertThat(step.lifecycle()).isEqualTo("done");
        assertThat(step.result()).isEqualTo("handoff 文本");
        assertThat(step.summary().after()).isEqualTo("完成");
        assertThat(step.endedAt()).isNotNull();
    }

    @Test
    void complete_error_marksError() {
        WorkerTimelineBridge bridge = new WorkerTimelineBridge("t1", "调研");

        List<StreamToken> tokens = bridge.complete("执行失败", "超时", false);

        ProcessingStep step = stepOf(tokens);
        assertThat(step.lifecycle()).isEqualTo("error");
        assertThat(step.result()).isEqualTo("超时");
        assertThat(step.endedAt()).isNotNull();
    }

    private static ProcessingStep stepOf(List<StreamToken> tokens) {
        assertThat(tokens).isNotEmpty();
        return tokens.get(0).step();
    }
}
