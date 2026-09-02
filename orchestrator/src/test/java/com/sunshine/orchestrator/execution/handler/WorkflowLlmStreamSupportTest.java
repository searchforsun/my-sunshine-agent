package com.sunshine.orchestrator.execution.handler;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.execution.WorkflowStreamCollector;
import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.NodeSpec;
import com.sunshine.orchestrator.execution.WorkflowContext;
import com.sunshine.orchestrator.prompt.PromptComposeRequest;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowLlmStreamSupportTest {

    @Test
    void terminalAnswer_preservesWhitespaceOnlyContent() {
        List<StreamToken> ws = WorkflowLlmStreamSupport.mapStreamToken(
                StreamToken.content("\n"), "node-answer", true).collectList().block();
        assertThat(ws).hasSize(1);
        assertThat(ws.get(0).isStepDelta()).isTrue();
        assertThat(ws.get(0).text()).isEqualTo("\n");
    }

    @Test
    void terminalAnswer_dropsReasoningTokens() {
        List<StreamToken> reasoning = WorkflowLlmStreamSupport.mapStreamToken(
                StreamToken.reasoning("meta 分析"), "node-answer", true).collectList().block();
        assertThat(reasoning).isEmpty();

        List<StreamToken> content = WorkflowLlmStreamSupport.mapStreamToken(
                StreamToken.content("正文"), "node-answer", true).collectList().block();
        assertThat(content).hasSize(1);
        assertThat(content.get(0).isStepDelta()).isTrue();
        assertThat(content.get(0).channel()).isEqualTo("result");
        assertThat(content.get(0).stepId()).isEqualTo("node-answer");
    }

    @Test
    void terminalAnswer_buildResultUsesContentOnly() {
        WorkflowStreamCollector collector = new WorkflowStreamCollector();
        collector.accept(StreamToken.stepDelta("node-answer", "reasoning", "应被忽略"));
        collector.accept(StreamToken.content("最终答复"));
        var result = WorkflowLlmStreamSupport.buildResult(collector, true);
        assertThat(result.safeOutputs()).doesNotContainKey("reasoning");
        assertThat(result.safeOutputs().get("answer").render()).isEqualTo("最终答复");
        assertThat(result.safeOutputs().get("detail").render()).isEqualTo("最终答复");
    }

    @Test
    void buildRequest_carriesPersonalRules() {
        NodeSpec spec = new NodeSpec("answer", "answer", Map.of("prompt", "总结"), "答复");
        WorkflowContext wfCtx = new WorkflowContext();
        ExecutionStreamContext streamCtx = new ExecutionStreamContext(
                "c1", "msg-1", "用户问题", AssembledContext.empty(),
                null, null, "u1", "default", null,
                new ExecutionPlan(ExecutionMode.WORKFLOW, "knowledge-qa", Map.of(), "test"),
                null, null, null, false, false, null, "用文言文回答", null);

        PromptComposeRequest request = WorkflowLlmStreamSupport.buildRequest(spec, wfCtx, streamCtx);

        assertThat(request.personalRules()).isEqualTo("用文言文回答");
        assertThat(request.workflowId()).isEqualTo("knowledge-qa");
    }

    @Test
    void buildRequest_withoutPersonalRulesPassesNull() {
        NodeSpec spec = new NodeSpec("llm-1", "llm", Map.of(), "处理");
        WorkflowContext wfCtx = new WorkflowContext();
        ExecutionStreamContext streamCtx = new ExecutionStreamContext(
                "c1", "msg-1", "用户问题", AssembledContext.empty(),
                null, null, "u1", "default",
                new ExecutionPlan(ExecutionMode.WORKFLOW, "knowledge-qa", Map.of(), "test"));

        PromptComposeRequest request = WorkflowLlmStreamSupport.buildRequest(spec, wfCtx, streamCtx);

        assertThat(request.personalRules()).isNull();
    }
}
