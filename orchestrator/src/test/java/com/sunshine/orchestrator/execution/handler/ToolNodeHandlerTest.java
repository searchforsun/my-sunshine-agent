package com.sunshine.orchestrator.execution.handler;

import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.client.ToolManagerClient;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.NodeSpec;
import com.sunshine.orchestrator.execution.WorkflowContext;
import com.sunshine.orchestrator.memory.MemoryContext;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import com.sunshine.orchestrator.hitl.WorkflowHitlScope;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolNodeHandlerTest {

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
    void invokesToolAndWritesOutput() {
        when(toolManagerClient.invoke(eq("list_finance_messages"), eq(Map.of("status", "pending"))))
                .thenReturn("{\"items\":[]}");

        WorkflowContext ctx = new WorkflowContext();
        ExecutionStreamContext streamCtx = new ExecutionStreamContext(
                "c1", "m1", "有哪些待审批", MemoryContext.empty(),
                null, null, null, "u1", "default",
                new ExecutionPlan(ExecutionMode.WORKFLOW, "finance-list", Map.of("status", "pending"), "test"));

        NodeSpec spec = new NodeSpec("finance-list", "tool",
                Map.of("tool", "list_finance_messages", "status", "pending"));

        var result = handler.run(spec, ctx, streamCtx).block();
        assertThat(result).isNotNull();
        assertThat(result.success()).isTrue();
        assertThat(result.safeOutputs().get("output")).contains("items");
        assertThat(result.safeOutputs().get("tool")).isEqualTo("list_finance_messages");
    }

    @Test
    void writeToolAwaitsWorkflowHitlBeforeInvoke() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        WorkflowHitlScope.Binding hitl = new WorkflowHitlScope.Binding(session, "node-approve", "m1");
        ExecutionStreamContext streamCtx = new ExecutionStreamContext(
                "c1", "m1", "审批", MemoryContext.empty(),
                null, null, null, "u1", "default",
                new ExecutionPlan(ExecutionMode.PLAN_WORKFLOW, "dynamic", Map.of(), "test"))
                .withWorkflowHitl(hitl);
        when(hitlConfirmationService.shouldConfirmWorkflow(eq("approve_oa_task"), eq(hitl))).thenReturn(true);
        when(hitlConfirmationService.awaitWorkflowConfirmation(eq(hitl), eq("m1"), eq("approve_oa_task"), any()))
                .thenReturn(true);
        when(toolManagerClient.invoke(eq("approve_oa_task"), eq(Map.of("taskId", "T1002"))))
                .thenReturn("已审批待办 T1002");

        NodeSpec spec = new NodeSpec("approve", "tool",
                Map.of("tool", "approve_oa_task", "taskId", "T1002"));

        var result = handler.run(spec, new WorkflowContext(), streamCtx).block();
        assertThat(result).isNotNull();
        assertThat(result.success()).isTrue();
        assertThat(result.safeOutputs().get("output")).contains("T1002");
        verify(hitlConfirmationService).awaitWorkflowConfirmation(eq(hitl), eq("m1"), eq("approve_oa_task"), any());
    }

    @Test
    void softInvokeFailureMarksNodeFailed() {
        when(toolManagerClient.invoke(eq("list_oa_tasks"), eq(Map.of())))
                .thenReturn("工具调用失败: Connection refused: getsockopt: localhost/127.0.0.1:8210");

        ExecutionStreamContext streamCtx = new ExecutionStreamContext(
                "c1", "m1", "查待办", MemoryContext.empty(),
                null, null, "plan-1", "u1", "default",
                new ExecutionPlan(ExecutionMode.PLAN_WORKFLOW, "dynamic", Map.of(), "test"));

        NodeSpec spec = new NodeSpec("list", "tool", Map.of("tool", "list_oa_tasks"));

        var result = handler.run(spec, new WorkflowContext(), streamCtx).block();
        assertThat(result).isNotNull();
        assertThat(result.success()).isFalse();
        assertThat(result.safeOutputs().get("error")).contains("Connection refused");
    }
}
