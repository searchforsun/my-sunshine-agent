package com.sunshine.common.workflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowNodeTypeTest {

    @Test
    void studioContainsStartAndLlm() {
        assertThat(WorkflowNodeType.studioTypeIds()).contains("start", "llm", "loop");
        assertThat(WorkflowNodeType.planExecTypeIds()).doesNotContain("start");
        assertThat(WorkflowNodeType.planExecTypeIds()).doesNotContain("llm");
    }

    @Test
    void plannerAndLoopBodyAlign() {
        assertThat(WorkflowNodeType.plannerTypeIds())
                .containsExactlyInAnyOrder("rag", "tool", "agent");
        assertThat(WorkflowNodeType.loopBodyTypeIds())
                .isEqualTo(WorkflowNodeType.plannerTypeIds());
    }

    @Test
    void isPlanChainNode() {
        assertThat(WorkflowNodeType.isPlanChainNode("rag")).isTrue();
        assertThat(WorkflowNodeType.isPlanChainNode("loop")).isTrue();
        assertThat(WorkflowNodeType.isPlanChainNode("start")).isFalse();
        assertThat(WorkflowNodeType.isPlanChainNode("answer")).isFalse();
        assertThat(WorkflowNodeType.isPlanChainNode("exclusive-gateway")).isFalse();
    }

    @Test
    void tracksNodeStep() {
        assertThat(WorkflowNodeType.tracksNodeStep("answer")).isTrue();
        assertThat(WorkflowNodeType.tracksNodeStep("loop")).isTrue();
        assertThat(WorkflowNodeType.tracksNodeStep("start")).isFalse();
        assertThat(WorkflowNodeType.tracksNodeStep("join")).isFalse();
        assertThat(WorkflowNodeType.tracksNodeStep("parallel-gateway")).isFalse();
    }

    @Test
    void isStreamingOutput() {
        assertThat(WorkflowNodeType.isStreamingOutput("llm")).isTrue();
        assertThat(WorkflowNodeType.isStreamingOutput("rag")).isFalse();
    }
}
