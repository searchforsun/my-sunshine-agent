package com.sunshine.orchestrator.execution;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowNodeTypeTest {

    @Test
    void isPlanChainNode() {
        assertThat(WorkflowNodeType.isPlanChainNode("rag")).isTrue();
        assertThat(WorkflowNodeType.isPlanChainNode("start")).isFalse();
        assertThat(WorkflowNodeType.isPlanChainNode("answer")).isFalse();
    }

    @Test
    void tracksNodeStep() {
        assertThat(WorkflowNodeType.tracksNodeStep("answer")).isTrue();
        assertThat(WorkflowNodeType.tracksNodeStep("rag")).isTrue();
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
