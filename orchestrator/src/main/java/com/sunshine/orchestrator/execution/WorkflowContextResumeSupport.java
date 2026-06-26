package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.plan.PlanNodeTrace;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Plan 续跑：补全 start / 从 execution_trace 回填上游输出 */
final class WorkflowContextResumeSupport {

    private WorkflowContextResumeSupport() {
    }

    static void prepare(
            WorkflowContext wfCtx,
            ExecutionStreamContext streamCtx,
            List<PlanNodeTrace> traces,
            WorkflowDefinition def) {
        ensureStartUserQuery(wfCtx, streamCtx);
        enrichFromTraces(wfCtx, traces, def);
    }

    private static void ensureStartUserQuery(WorkflowContext wfCtx, ExecutionStreamContext streamCtx) {
        Map<String, String> start = new LinkedHashMap<>(wfCtx.node("start"));
        if (!StringUtils.hasText(start.get("userQuery")) && StringUtils.hasText(streamCtx.userContent())) {
            start.put("userQuery", streamCtx.userContent().strip());
            wfCtx.putNode("start", start);
        }
    }

    private static void enrichFromTraces(
            WorkflowContext wfCtx,
            List<PlanNodeTrace> traces,
            WorkflowDefinition def) {
        if (traces == null || traces.isEmpty()) {
            return;
        }
        for (PlanNodeTrace trace : traces) {
            if (trace == null || !"completed".equals(trace.status())) {
                continue;
            }
            String nodeId = trace.nodeId();
            if (!StringUtils.hasText(nodeId)) {
                continue;
            }
            Map<String, String> existing = wfCtx.node(nodeId);
            if (StringUtils.hasText(existing.get("output"))) {
                continue;
            }
            String payload = StringUtils.hasText(trace.detail()) ? trace.detail() : trace.summary();
            if (!StringUtils.hasText(payload)) {
                continue;
            }
            Map<String, String> outputs = new LinkedHashMap<>(existing);
            outputs.put("output", payload.strip());
            outputs.put("detail", payload.strip());
            if ("tool".equals(trace.type()) && def != null) {
                NodeSpec spec = def.node(nodeId);
                if (spec != null && spec.params() != null) {
                    String tool = spec.params().get("tool");
                    if (StringUtils.hasText(tool)) {
                        outputs.put("tool", tool.strip());
                    }
                }
            }
            wfCtx.putNode(nodeId, outputs);
        }
    }
}
