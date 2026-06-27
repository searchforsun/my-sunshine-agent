package com.sunshine.orchestrator.execution.handler;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.execution.WorkflowStreamCollector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowLlmStreamSupportTest {

    @Test
    void terminalAnswer_dropsReasoningTokens() {
        List<StreamToken> reasoning = WorkflowLlmStreamSupport.mapStreamToken(
                StreamToken.reasoning("meta 分析"), "node-answer", true).collectList().block();
        assertThat(reasoning).isEmpty();

        List<StreamToken> content = WorkflowLlmStreamSupport.mapStreamToken(
                StreamToken.content("正文"), "node-answer", true).collectList().block();
        assertThat(content).hasSize(2);
        assertThat(content.get(0).isContent()).isTrue();
        assertThat(content.get(1).isStepDelta()).isTrue();
        assertThat(content.get(1).channel()).isEqualTo("result");
        assertThat(content.get(1).stepId()).isEqualTo("node-answer");
    }

    @Test
    void terminalAnswer_buildResultUsesContentOnly() {
        WorkflowStreamCollector collector = new WorkflowStreamCollector();
        collector.accept(StreamToken.stepDelta("node-answer", "reasoning", "应被忽略"));
        collector.accept(StreamToken.content("最终答复"));
        var result = WorkflowLlmStreamSupport.buildResult(collector, true);
        assertThat(result.safeOutputs()).doesNotContainKey("reasoning");
        assertThat(result.safeOutputs().get("answer")).isEqualTo("最终答复");
        assertThat(result.safeOutputs().get("detail")).isEqualTo("最终答复");
    }
}
