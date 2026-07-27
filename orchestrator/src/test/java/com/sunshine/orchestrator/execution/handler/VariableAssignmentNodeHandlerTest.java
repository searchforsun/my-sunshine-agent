package com.sunshine.orchestrator.execution.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.NodeResult;
import com.sunshine.orchestrator.execution.NodeSpec;
import com.sunshine.orchestrator.execution.TypedValue;
import com.sunshine.orchestrator.execution.WorkflowContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class VariableAssignmentNodeHandlerTest {

    private final VariableAssignmentNodeHandler handler = new VariableAssignmentNodeHandler();

    @Test
    void assignmentsResolvedToOutputs() {
        var ctx = new WorkflowContext();
        var data = new ObjectMapper().createObjectNode();
        data.put("id", "exp-001");
        data.put("total", 100);
        ctx.putNode("tool_1", Map.of("output", TypedValue.fromJson(data)));

        var spec = new NodeSpec("var_1", "variable-assignment",
                Map.of("assignments", """
                    [{"name":"expenseId","source":"{{tool_1.output.id}}","type":"string"},
                     {"name":"totalAmount","source":"{{tool_1.output.total}}","type":"number"}]
                    """),
                "提取变量");

        NodeResult result = handler.run(spec, ctx, mock(ExecutionStreamContext.class)).block();
        assertThat(result.success()).isTrue();
        assertThat(result.safeOutputs().get("expenseId").render()).isEqualTo("exp-001");
        assertThat(result.safeOutputs().get("totalAmount").render()).isEqualTo("100");
    }

    @Test
    void emptyAssignmentsReturnsEmptyOk() {
        var ctx = new WorkflowContext();
        var spec = new NodeSpec("var_2", "variable-assignment",
                Map.of("assignments", "[]"), "空赋值");
        NodeResult result = handler.run(spec, ctx, mock(ExecutionStreamContext.class)).block();
        assertThat(result.success()).isTrue();
        assertThat(result.safeOutputs()).isEmpty();
    }

    @Test
    void invalidJsonFailsNode() {
        var ctx = new WorkflowContext();
        var spec = new NodeSpec("var_3", "variable-assignment",
                Map.of("assignments", "not-json"), "错误");
        NodeResult result = handler.run(spec, ctx, mock(ExecutionStreamContext.class)).block();
        assertThat(result.success()).isFalse();
    }
}
