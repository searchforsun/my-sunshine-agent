package com.sunshine.orchestrator.execution.handler;

import com.sunshine.orchestrator.client.LlmGatewayClient;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.NodeSpec;
import com.sunshine.orchestrator.execution.WorkflowContext;
import com.sunshine.orchestrator.execution.WorkflowStreamCollector;
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
import reactor.core.publisher.Flux;

import java.util.List;
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

        when(llmGateway.streamComposed(any())).thenReturn(Flux.just(
                StreamToken.reasoning("先核对制度"),
                StreamToken.content("工作满1年可休5天年假")));

        var result = handler.run(spec, ctx, streamCtx).block();
        assertThat(result).isNotNull();
        assertThat(result.success()).isTrue();
        assertThat(result.safeOutputs().get("answer")).isEqualTo("工作满1年可休5天年假");
        assertThat(result.safeOutputs().get("reasoning")).isEqualTo("先核对制度");

        ArgumentCaptor<PromptComposeRequest> captor = ArgumentCaptor.forClass(PromptComposeRequest.class);
        verify(llmGateway).streamComposed(captor.capture());
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

        when(llmGateway.streamComposed(any())).thenReturn(Flux.just(StreamToken.content("报销需提前申请")));

        handler.run(spec, new WorkflowContext(), streamCtx).block();

        ArgumentCaptor<PromptComposeRequest> captor = ArgumentCaptor.forClass(PromptComposeRequest.class);
        verify(llmGateway).streamComposed(captor.capture());
        assertThat(captor.getValue().userMessage()).isEqualTo("报销流程");
    }

    @Test
    void streamTokens_mapsReasoningToNodeStepDelta() {
        NodeSpec spec = new NodeSpec("n4", "llm", Map.of("prompt", "综合输出"));
        ExecutionStreamContext streamCtx = new ExecutionStreamContext(
                "c1", "m1", "q", MemoryContext.empty(),
                null, null, null, "u1", "default",
                new ExecutionPlan(ExecutionMode.PLAN_WORKFLOW, null, Map.of(), "plan"));
        when(llmGateway.streamComposed(any())).thenReturn(Flux.just(
                StreamToken.reasoning("分析中"),
                StreamToken.content("结论")));

        List<StreamToken> tokens = handler.streamTokens(spec, new WorkflowContext(), streamCtx, "n4")
                .collectList().block();
        assertThat(tokens).hasSize(2);
        assertThat(tokens.get(0).isStepDelta()).isTrue();
        assertThat(tokens.get(0).stepId()).isEqualTo("node-n4");
        assertThat(tokens.get(1).isContent()).isTrue();
    }

    @Test
    void buildResult_collectsReasoningAndContent() {
        WorkflowStreamCollector collector = new WorkflowStreamCollector();
        collector.accept(StreamToken.stepDelta("node-n4", "reasoning", "思考"));
        collector.accept(StreamToken.content("正文"));
        var result = handler.buildResult(collector);
        assertThat(result.safeOutputs().get("reasoning")).isEqualTo("思考");
        assertThat(result.safeOutputs().get("answer")).isEqualTo("正文");
    }
}
