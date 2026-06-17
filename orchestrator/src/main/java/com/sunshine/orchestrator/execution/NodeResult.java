package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.client.StreamToken;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 节点执行结果 — 写入 WorkflowContext，并可选携带 Timeline token
 */
public record NodeResult(
        boolean success,
        Map<String, String> outputs,
        List<StreamToken> timelineTokens,
        List<StreamToken> contentTokens
) {
    public static NodeResult ok(Map<String, String> outputs) {
        return new NodeResult(true, outputs, List.of(), List.of());
    }

    public static NodeResult ok(Map<String, String> outputs, List<StreamToken> timelineTokens) {
        return new NodeResult(true, outputs,
                timelineTokens != null ? timelineTokens : List.of(), List.of());
    }

    public static NodeResult withContent(Map<String, String> outputs, List<StreamToken> contentTokens) {
        return new NodeResult(true, outputs, List.of(),
                contentTokens != null ? contentTokens : List.of());
    }

    public static NodeResult fail(String message) {
        return new NodeResult(false, Map.of("error", message), List.of(), List.of());
    }

    public Map<String, String> safeOutputs() {
        return outputs != null ? outputs : Collections.emptyMap();
    }
}
