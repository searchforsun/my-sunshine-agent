package com.sunshine.orchestrator.execution.handler;

import com.sunshine.orchestrator.client.LlmGatewayClient;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.NodeSpec;
import com.sunshine.orchestrator.execution.WorkflowContext;
import com.sunshine.orchestrator.memory.MemoryContext;
import com.sunshine.orchestrator.prompt.PromptComposeRequest;
import com.sunshine.orchestrator.prompt.PromptMode;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LlmNodeHandlerTest {

    @Mock
    private LlmGatewayClient llmGateway;

    private LlmNodeHandler handler;

    @BeforeEach
    void setUp() {
        handler = new LlmNodeHandler(llmGateway);
    }

    @Test
    void run_usesPromptComposerWithWorkflowNodePrompt() {
        WorkflowContext ctx = new WorkflowContext();
        ctx.put("start", Map.of("userQuery", "年假可以请几天"));
        ctx.put("rag", Map.of("output", "员工年假制度：工作满1年5天"));

        String resolvedPrompt = """
                你是企业制度问答助手。仅根据下方「检索结果」回答。

                检索结果：
                员工年假制度：工作满1年5天""";
        NodeSpec spec = new NodeSpec("llm", "llm", Map.of("prompt", resolvedPrompt));

        ExecutionStreamContext streamCtx = new ExecutionStreamContext(
                "c1", "m1", "年假可以请几天", MemoryContext.empty(),
                null, null, null, "u1", "default",
                new ExecutionPlan(ExecutionMode.WORKFLOW, "knowledge-qa", Map.of(), "查制度"));

        when(llmGateway.completeComposed(any())).thenReturn("工作满1年可休5天年假");

        var result = handler.run(spec, ctx, streamCtx).block();
        assertThat(result).isNotNull();
        assertThat(result.success()).isTrue();
        assertThat(result.safeOutputs().get("answer")).isEqualTo("工作满1年可休5天年假");

        ArgumentCaptor<PromptComposeRequest> captor = ArgumentCaptor.forClass(PromptComposeRequest.class);
        verify(llmGateway).completeComposed(captor.capture());
        PromptComposeRequest request = captor.getValue();
        assertThat(request.mode()).isEqualTo(PromptMode.WORKFLOW);
        assertThat(request.workflowId()).isEqualTo("knowledge-qa");
        assertThat(request.userMessage()).isEqualTo("年假可以请几天");
        assertThat(request.nodePrompt()).contains("员工年假制度");
    }

    @Test
    void run_fallsBackToStreamUserContentWhenStartQueryMissing() {
        NodeSpec spec = new NodeSpec("llm", "llm", Map.of("prompt", "回答用户问题"));
        ExecutionStreamContext streamCtx = new ExecutionStreamContext(
                "c1", "m1", "报销流程", MemoryContext.empty(),
                null, null, null, "u1", "default",
                new ExecutionPlan(ExecutionMode.WORKFLOW, "finance-list", Map.of(), "查报销"));

        when(llmGateway.completeComposed(any())).thenReturn("报销需提前申请");

        handler.run(spec, new WorkflowContext(), streamCtx).block();

        ArgumentCaptor<PromptComposeRequest> captor = ArgumentCaptor.forClass(PromptComposeRequest.class);
        verify(llmGateway).completeComposed(captor.capture());
        assertThat(captor.getValue().userMessage()).isEqualTo("报销流程");
    }
}
