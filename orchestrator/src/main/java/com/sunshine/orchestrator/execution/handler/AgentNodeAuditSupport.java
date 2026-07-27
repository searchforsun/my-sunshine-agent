package com.sunshine.orchestrator.execution.handler;

import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.audit.SubAgentAuditService;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.NodeResult;
import com.sunshine.orchestrator.execution.NodeSpec;
import com.sunshine.orchestrator.execution.TypedValue;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** 子 Agent 审计落库 */
final class AgentNodeAuditSupport {

    private AgentNodeAuditSupport() {
    }

    static void auditSuccess(
            SubAgentAuditService subAgentAuditService,
            NodeSpec spec,
            ExecutionStreamContext streamCtx,
            AgentRunRequest request,
            NodeResult result,
            String skillId) {
        if (result == null || !result.success()) {
            return;
        }
        String summary = renderScalarFirst(result, "detail", "answer");
        subAgentAuditService.subAgentRun(
                streamCtx.conversationId(),
                streamCtx.assistantMsgId(),
                streamCtx.userId(),
                streamCtx.tenantId(),
                streamCtx.persistedPlanId(),
                spec.id(),
                request.runId(),
                skillId,
                parseToolCalls(renderScalar(result, "toolCalls")),
                summary,
                "ok");
    }

    static void auditFailure(
            SubAgentAuditService subAgentAuditService,
            NodeSpec spec,
            ExecutionStreamContext streamCtx,
            AgentRunRequest request,
            String skillId,
            String error) {
        subAgentAuditService.subAgentRun(
                streamCtx.conversationId(),
                streamCtx.assistantMsgId(),
                streamCtx.userId(),
                streamCtx.tenantId(),
                streamCtx.persistedPlanId(),
                spec.id(),
                request.runId(),
                skillId,
                List.of(),
                error,
                "failed");
    }

    private static List<String> parseToolCalls(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::strip)
                .filter(StringUtils::hasText)
                .toList();
    }

    private static String renderScalar(NodeResult result, String key) {
        TypedValue v = result.safeOutputs().get(key);
        return v != null ? v.render() : null;
    }

    private static String renderScalarFirst(NodeResult result, String k1, String k2) {
        String v1 = renderScalar(result, k1);
        if (v1 != null && !v1.isBlank()) {
            return v1;
        }
        String v2 = renderScalar(result, k2);
        return v2 != null ? v2 : "";
    }
}
