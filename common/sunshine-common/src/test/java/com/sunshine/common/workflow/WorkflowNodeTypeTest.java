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
        // planner 允许业务 + 路由 type（start/answer 由引擎固定）
        assertThat(WorkflowNodeType.plannerTypeIds())
                .contains("rag", "tool", "agent", "join", "parallel-gateway", "exclusive-gateway", "loop");
        // loop body 仅业务节点 + variable-assignment（parameter-extractor 不进 loop body）
        assertThat(WorkflowNodeType.loopBodyTypeIds())
                .containsExactlyInAnyOrder("rag", "tool", "agent", "variable-assignment");
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
