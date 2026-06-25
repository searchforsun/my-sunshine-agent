package com.sunshine.orchestrator.execution.handler;

import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.client.ToolManagerClient;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.NodeSpec;
import com.sunshine.orchestrator.execution.WorkflowContext;
import com.sunshine.orchestrator.memory.MemoryContext;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolNodeHandlerTest {

    @Mock
    private ToolManagerClient toolManagerClient;

    @Mock
    private ToolCatalogService toolCatalogService;

    @Mock
    private com.sunshine.orchestrator.audit.ToolAuditService toolAuditService;

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
}
