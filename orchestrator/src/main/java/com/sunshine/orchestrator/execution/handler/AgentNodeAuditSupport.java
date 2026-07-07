package com.sunshine.orchestrator.execution.handler;

import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.audit.SubAgentAuditService;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.NodeResult;
import com.sunshine.orchestrator.execution.NodeSpec;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;

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
        String summary = result.safeOutputs().getOrDefault("detail", result.safeOutputs().getOrDefault("answer", ""));
        subAgentAuditService.subAgentRun(
                streamCtx.conversationId(),
                streamCtx.assistantMsgId(),
                streamCtx.userId(),
                streamCtx.tenantId(),
                streamCtx.persistedPlanId(),
                spec.id(),
                request.runId(),
                skillId,
                parseToolCalls(result.safeOutputs().get("toolCalls")),
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
}
