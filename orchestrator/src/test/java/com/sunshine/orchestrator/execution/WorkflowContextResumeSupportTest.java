package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.memory.MemoryContext;
import com.sunshine.orchestrator.plan.PlanNodeTrace;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowContextResumeSupportTest {

    @Test
    void enrichFromTraces_fillsMissingToolOutput() {
        WorkflowContext wfCtx = new WorkflowContext();
        wfCtx.putNode("start", Map.of("userQuery", "q"));
        WorkflowDefinition def = WorkflowDefinition.from(
                "p1",
                List.of(new NodeSpec("t1", "tool", Map.of("tool", "list_oa_tasks"), "查待办")),
                List.of("t1"));
        ExecutionStreamContext streamCtx = new ExecutionStreamContext(
                "c", "m", "查待办", MemoryContext.empty(), "", "", null,
                "u", "t",
                new ExecutionPlan(ExecutionMode.PLAN_WORKFLOW, null, Map.of(), "r"))
                .withPersistedPlanId("plan-1");

        WorkflowContextResumeSupport.prepare(
                wfCtx,
                streamCtx,
                List.of(new PlanNodeTrace(
                        "t1", "tool", "completed", "查待办完成", "[{\"taskId\":\"T1\"}]", 1L, 2L)),
                def);

        assertThat(wfCtx.resolvePath("t1.output")).contains("T1");
        assertThat(wfCtx.resolvePath("start.userQuery")).isEqualTo("q");
    }

    @Test
    void hasNodes_detectsEmptyAndPopulated() {
        assertThat(WorkflowContextCodec.hasNodes("{}")).isFalse();
        assertThat(WorkflowContextCodec.hasNodes(
                WorkflowContextCodec.toJson(new WorkflowContext()))).isFalse();
        WorkflowContext ctx = new WorkflowContext();
        ctx.putNode("t1", Map.of("output", "data"));
        assertThat(WorkflowContextCodec.hasNodes(WorkflowContextCodec.toJson(ctx))).isTrue();
    }
}
