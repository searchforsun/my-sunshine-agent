package com.sunshine.orchestrator.execution.handler;

import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.NodeSpec;
import com.sunshine.orchestrator.execution.WorkflowContext;
import com.sunshine.orchestrator.prompt.PromptComposeRequest;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowLlmStreamSupportTest {

    @Test
    void buildRequest_carriesPersonalRules() {
        NodeSpec spec = new NodeSpec("answer", "answer", Map.of("prompt", "总结"), "答复");
        WorkflowContext wfCtx = new WorkflowContext();
        ExecutionStreamContext streamCtx = new ExecutionStreamContext(
                "c1", "msg-1", "用户问题", AssembledContext.empty(),
                null, null, "u1", "default", null,
                new ExecutionPlan(ExecutionMode.WORKFLOW, "knowledge-qa", Map.of(), "test"),
                null, null, null, false, false, null, "用文言文回答");

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
