package com.sunshine.orchestrator.execution.handler;

import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.NodeSpec;
import com.sunshine.orchestrator.execution.WorkflowContext;
import com.sunshine.orchestrator.memory.MemoryContext;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Workflow agent 节点 params → AgentRunRequest */
final class AgentNodeRequestAssembler {

    private AgentNodeRequestAssembler() {
    }

    static AgentRunRequest build(NodeSpec spec, WorkflowContext ctx, ExecutionStreamContext streamCtx) {
        Map<String, String> params = spec.params() != null ? spec.params() : Map.of();
        String query = params.getOrDefault("query", ctx.resolvePath("start.userQuery"));
        List<String> injected = parseInjectedBlocks(params.getOrDefault("context", ""));
        String skillId = blankToNull(params.get("skill"));
        List<String> tools = parseToolList(params.get("tools"));
        int maxIters = parseMaxIters(params.get("maxIters"));
        return AgentRunRequest.sub(
                MemoryContext.forSubAgent(),
                query,
                injected,
                streamCtx.userId(),
                streamCtx.tenantId(),
                streamCtx.assistantMsgId(),
                skillId,
                tools,
                blankToNull(params.get("systemOverlay")),
                maxIters);
    }

    static List<String> parseInjectedBlocks(String context) {
        if (context == null || context.isBlank()) {
            return List.of();
        }
        return List.of(context.strip());
    }

    static List<String> parseToolList(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String normalized = raw.strip();
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1).strip();
        }
        List<String> tools = Arrays.stream(normalized.split(","))
                .map(String::strip)
                .filter(StringUtils::hasText)
                .toList();
        return tools.isEmpty() ? null : tools;
    }

    static int parseMaxIters(String raw) {
        if (!StringUtils.hasText(raw)) {
            return 0;
        }
        try {
            int value = Integer.parseInt(raw.strip());
            return value > 0 ? value : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }
}
