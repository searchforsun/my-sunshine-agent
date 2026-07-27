package com.sunshine.orchestrator.execution.handler;

import com.sunshine.orchestrator.client.LlmGatewayClient;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.NodeResult;
import com.sunshine.orchestrator.execution.NodeSpec;
import com.sunshine.orchestrator.execution.TypedValue;
import com.sunshine.orchestrator.execution.WorkflowContext;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ParameterExtractorNodeHandlerTest {

    private LlmGatewayClient llmGatewayClient;
    private PromptCatalogHolder promptCatalogHolder;
    private ParameterExtractorNodeHandler handler;

    @BeforeEach
    void setUp() {
        llmGatewayClient = mock(LlmGatewayClient.class);
        promptCatalogHolder = mock(PromptCatalogHolder.class);
        when(promptCatalogHolder.requireText("parameter-extractor.template"))
                .thenReturn("系统提示词：{{instruction}}\nSchema: {{schema}}\n输入: {{input}}");
        handler = new ParameterExtractorNodeHandler(llmGatewayClient, promptCatalogHolder);
    }

    @Test
    void extractsStructuredParametersFromText() {
        var ctx = new WorkflowContext();
        ctx.putNode("agent_1", Map.of("output", TypedValue.scalar("审批人张三同意了报销，金额200元")));

        when(llmGatewayClient.complete(any(), any())).thenReturn("""
            {"approver":"张三","result":"approved","comment":"同意报销"}
            """);

        var spec = new NodeSpec("extract_1", "parameter-extractor",
                Map.of("input", "{{agent_1.output}}",
                       "instruction", "提取审批人、结果、意见",
                       "schema", """
                           {"approver":{"type":"string"},"result":{"type":"string","enum":["approved","rejected"]},"comment":{"type":"string"}}
                           """),
                "参数提取");

        NodeResult result = handler.run(spec, ctx, mock(ExecutionStreamContext.class)).block();
        assertThat(result.success()).isTrue();
        assertThat(result.safeOutputs().get("approver").render()).isEqualTo("张三");
        assertThat(result.safeOutputs().get("result").render()).isEqualTo("approved");
    }

    @Test
    void llmReturnsInvalidJsonFailsNode() {
        var ctx = new WorkflowContext();
        ctx.putNode("agent_1", Map.of("output", TypedValue.scalar("some text")));

        when(llmGatewayClient.complete(any(), any())).thenReturn("not json");

        var spec = new NodeSpec("extract_2", "parameter-extractor",
                Map.of("input", "{{agent_1.output}}",
                       "instruction", "提取",
                       "schema", "{}"),
                "参数提取");

        NodeResult result = handler.run(spec, ctx, mock(ExecutionStreamContext.class)).block();
        assertThat(result.success()).isFalse();
    }
}
