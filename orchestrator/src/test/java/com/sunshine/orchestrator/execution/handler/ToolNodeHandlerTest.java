package com.sunshine.orchestrator.execution.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.client.ToolManagerClient;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.NodeSpec;
import com.sunshine.orchestrator.execution.TypedValue;
import com.sunshine.orchestrator.execution.WorkflowContext;
import com.sunshine.orchestrator.execution.InputBinding;
import com.sunshine.orchestrator.execution.VarType;
import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import com.sunshine.orchestrator.hitl.WorkflowHitlScope;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolNodeHandlerTest {

    private final ObjectMapper om = new ObjectMapper();

    @Mock
    private ToolManagerClient toolManagerClient;

    @Mock
    private ToolCatalogService toolCatalogService;

    @Mock
    private com.sunshine.orchestrator.audit.ToolAuditService toolAuditService;

    @Mock
    private com.sunshine.orchestrator.hitl.HitlConfirmationService hitlConfirmationService;

    @InjectMocks
    private ToolNodeHandler handler;

    @Test
    void invokesToolAndWritesStructuredOutput() {
        ObjectNode toolOutput = om.createObjectNode();
        toolOutput.put("items", om.createArrayNode());
        when(toolManagerClient.invokeJsonMono(
                eq("sdk__sunshine-finance__list_my_expenses"),
                anyMap(),
                anyString(),
                anyString()))
                .thenReturn(Mono.just(toolOutput));
        when(toolCatalogService.timelineSummary(anyString(), anyString())).thenReturn("");

        WorkflowContext ctx = new WorkflowContext();
        ExecutionStreamContext streamCtx = new ExecutionStreamContext(
                "c1", "m1", "有哪些待审批", AssembledContext.empty(),
                null, null, "u1", "default",
                new ExecutionPlan(ExecutionMode.WORKFLOW, "finance-list", Map.of("status", "pending"), "test"));

        NodeSpec spec = new NodeSpec("finance-list", "tool",
                Map.of("tool", "sdk__sunshine-finance__list_my_expenses", "status", "pending"),
                List.of(new InputBinding("status", "pending", VarType.STRING, false)),
                "查待审批");

        var result = handler.run(spec, ctx, streamCtx).block();
        assertThat(result).isNotNull();
        assertThat(result.success()).isTrue();
        assertThat(result.safeOutputs().get("output")).isInstanceOf(TypedValue.JsonObject.class);
        assertThat(result.safeOutputs().get("output").toJson().has("items")).isTrue();
        assertThat(result.safeOutputs().get("tool").render()).isEqualTo("sdk__sunshine-finance__list_my_expenses");
    }

    @Test
    void inputsBindingResolvesAndInvokesWithStructuredParams() {
        ObjectNode toolOutput = om.createObjectNode();
        toolOutput.put("id", "exp-001");
        toolOutput.put("amount", 100);
        WorkflowContext ctx = new WorkflowContext();
        ctx.putNode("tool_1", Map.of("output", TypedValue.fromJson(toolOutput)));

        NodeSpec spec = new NodeSpec("tool_2", "tool",
                Map.of("tool", "sdk__finance__get_detail"),
                List.of(new InputBinding("expenseId", "{{tool_1.output.id}}", VarType.STRING, true)),
                "查询报销详情");

        when(toolManagerClient.invokeJsonMono(eq("sdk__finance__get_detail"), anyMap(), any(), any()))
                .thenReturn(Mono.just(toolOutput));
        when(toolCatalogService.timelineSummary(anyString(), anyString())).thenReturn("");

        var result = handler.run(spec, ctx, newStreamCtx()).block();

        assertThat(result).isNotNull();
        assertThat(result.success()).isTrue();
        assertThat(result.safeOutputs().get("output")).isInstanceOf(TypedValue.JsonObject.class);
        assertThat(result.safeOutputs().get("output").toJson().get("id").asText()).isEqualTo("exp-001");
        verify(toolManagerClient).invokeJsonMono(eq("sdk__finance__get_detail"),
                org.mockito.ArgumentMatchers.argThat(m -> {
                    Object v = m.get("expenseId");
                    if (v == null) {
                        return false;
                    }
                    String s = v instanceof com.fasterxml.jackson.databind.JsonNode n
                            ? n.asText() : v.toString();
                    return "exp-001".equals(s);
                }),
                any(), any());
    }

    @Test
    void missingRequiredInputFailsNode() {
        WorkflowContext ctx = new WorkflowContext();
        NodeSpec spec = new NodeSpec("tool_1", "tool",
                Map.of("tool", "sdk__test__tool"),
                List.of(new InputBinding("id", "{{missing.output}}", VarType.STRING, true)),
                "测试");

        var result = handler.run(spec, ctx, newStreamCtx()).block();

        assertThat(result).isNotNull();
        assertThat(result.success()).isFalse();
        assertThat(result.safeOutputs().get("error").render()).contains("缺少必填参数");
    }

    @Test
    void writeToolAwaitsWorkflowHitlBeforeInvoke() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        WorkflowHitlScope.Binding hitl = new WorkflowHitlScope.Binding(session, "node-approve", "m1");
        ExecutionStreamContext streamCtx = new ExecutionStreamContext(
                "c1", "m1", "审批", AssembledContext.empty(),
                null, null, "u1", "default",
                new ExecutionPlan(ExecutionMode.PRO, "dynamic", Map.of(), "test"))
                .withWorkflowHitl(hitl);
        when(hitlConfirmationService.shouldConfirmWorkflow(eq("sdk__sunshine-oa__approve_oa_task"), eq(hitl))).thenReturn(true);
        when(hitlConfirmationService.awaitWorkflowConfirmation(eq(hitl), eq("m1"), eq("sdk__sunshine-oa__approve_oa_task"), any()))
                .thenReturn(true);
        ObjectNode ok = om.createObjectNode();
        ok.put("output", "已审批待办 T1002");
        when(toolManagerClient.invokeJsonMono(
                eq("sdk__sunshine-oa__approve_oa_task"),
                anyMap(),
                anyString(),
                anyString()))
                .thenReturn(Mono.just(ok));
        when(toolCatalogService.timelineSummary(anyString(), anyString())).thenReturn("");

        NodeSpec spec = new NodeSpec("approve", "tool",
                Map.of("tool", "sdk__sunshine-oa__approve_oa_task"),
                List.of(new InputBinding("taskId", "T1002", VarType.STRING, false)),
                "审批");

        var result = handler.run(spec, new WorkflowContext(), streamCtx).block();
        assertThat(result).isNotNull();
        assertThat(result.success()).isTrue();
        assertThat(result.safeOutputs().get("output").render()).contains("T1002");
        verify(hitlConfirmationService).awaitWorkflowConfirmation(eq(hitl), eq("m1"), eq("sdk__sunshine-oa__approve_oa_task"), any());
    }

    @Test
    void softInvokeFailureMarksNodeFailed() {
        ObjectNode err = om.createObjectNode();
        err.put("error", ToolManagerClient.INVOKE_FAILURE_PREFIX + " Connection refused: getsockopt: localhost/127.0.0.1:8210");
        when(toolManagerClient.invokeJsonMono(
                eq("sdk__sunshine-oa__list_oa_tasks"),
                anyMap(),
                anyString(),
                anyString()))
                .thenReturn(Mono.just(err));

        ExecutionStreamContext streamCtx = newStreamCtx();

        NodeSpec spec = new NodeSpec("list", "tool",
                Map.of("tool", "sdk__sunshine-oa__list_oa_tasks"), "查待办");

        var result = handler.run(spec, new WorkflowContext(), streamCtx).block();
        assertThat(result).isNotNull();
        assertThat(result.success()).isFalse();
        assertThat(result.safeOutputs().get("error").render()).contains("Connection refused");
    }

    private static ExecutionStreamContext newStreamCtx() {
        return new ExecutionStreamContext(
                "c1", "m1", "查待办", AssembledContext.empty(),
                null, null, "u1", "default",
                new ExecutionPlan(ExecutionMode.PRO, "dynamic", Map.of(), "test"));
    }
}
