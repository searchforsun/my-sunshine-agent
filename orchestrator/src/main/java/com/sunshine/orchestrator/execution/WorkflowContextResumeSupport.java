package com.sunshine.orchestrator.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        Map<String, TypedValue> start = new LinkedHashMap<>(wfCtx.node("start"));
        TypedValue existing = start.get("userQuery");
        boolean hasQuery = existing != null && StringUtils.hasText(existing.render());
        if (!hasQuery && StringUtils.hasText(streamCtx.userContent())) {
            start.put("userQuery", TypedValue.scalar(streamCtx.userContent().strip()));
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
            Map<String, TypedValue> existing = wfCtx.node(nodeId);
            TypedValue existingOutput = existing.get("output");
            if (existingOutput != null && StringUtils.hasText(existingOutput.render())) {
                continue;
            }
            String payload = StringUtils.hasText(trace.detail()) ? trace.detail() : trace.summary();
            if (!StringUtils.hasText(payload)) {
                continue;
            }
            String trimmedPayload = payload.strip();
            TypedValue outputValue = parseStructuredOrScalar(trimmedPayload);
            Map<String, TypedValue> outputs = new LinkedHashMap<>(existing);
            outputs.put("output", outputValue);
            outputs.put("detail", TypedValue.scalar(trimmedPayload));
            if ("tool".equals(trace.type()) && def != null) {
                NodeSpec spec = def.node(nodeId);
                if (spec != null && spec.params() != null) {
                    Object toolObj = spec.params().get("tool");
                    if (toolObj != null && StringUtils.hasText(toolObj.toString())) {
                        outputs.put("tool", TypedValue.scalar(toolObj.toString().strip()));
                    }
                }
            }
            wfCtx.putNode(nodeId, outputs);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 续跑回填：trace payload 若为 JSON 对象/数组则保留结构化，否则退化为 scalar */
    private static TypedValue parseStructuredOrScalar(String payload) {
        if (payload == null || payload.isBlank()) {
            return TypedValue.scalar("");
        }
        String trimmed = payload.strip();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                JsonNode node = JSON.readTree(trimmed);
                if (node.isObject() || node.isArray()) {
                    return TypedValue.fromJson(node);
                }
            } catch (Exception ignored) {
                // 非 JSON，回退 scalar
            }
        }
        return TypedValue.scalar(trimmed);
    }
}
