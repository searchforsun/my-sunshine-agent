package com.sunshine.orchestrator.execution.agent;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.client.StreamToken;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SubAgentTimelineBridgeTest {

    @Test
    void wrap_mapsSubStepsOntoNodeStep() {
        SubAgentTimelineBridge bridge = new SubAgentTimelineBridge("analyze", "合规分析");
        bridge.wrap(StreamToken.step(ProcessingStep.running("think", "think", "思考过程")));
        bridge.wrap(StreamToken.stepDelta("think", "reasoning", "先查制度"));

        List<StreamToken> tokens = bridge.wrap(StreamToken.step(
                ProcessingStep.done("tool-search_knowledge", "tool", "检索知识库", "命中 3 条")));

        assertThat(tokens).hasSize(1);
        ProcessingStep node = tokens.get(0).step();
        assertThat(node.id()).isEqualTo("node-analyze");
        assertThat(node.subSteps()).hasSize(2);
        assertThat(node.subSteps().stream().map(ProcessingStep::id))
                .containsExactly("think", "tool-search_knowledge");
    }
}
