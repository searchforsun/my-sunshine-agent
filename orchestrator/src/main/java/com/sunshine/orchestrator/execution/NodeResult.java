package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.client.StreamToken;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点执行结果 - 写入 WorkflowContext，并可选携带 Timeline token
 */
public record NodeResult(
        boolean success,
        Map<String, TypedValue> outputs,
        List<StreamToken> timelineTokens,
        List<StreamToken> contentTokens
) {
    public static NodeResult ok(Map<String, TypedValue> outputs) {
        return new NodeResult(true, outputs, List.of(), List.of());
    }

    public static NodeResult ok(Map<String, TypedValue> outputs, List<StreamToken> timelineTokens) {
        return new NodeResult(true, outputs,
                timelineTokens != null ? timelineTokens : List.of(), List.of());
    }

    public static NodeResult withContent(Map<String, TypedValue> outputs, List<StreamToken> contentTokens) {
        return new NodeResult(true, outputs, List.of(),
                contentTokens != null ? contentTokens : List.of());
    }

    /** String outputs 自动转 Scalar（handler 主力构造方法） */
    public static NodeResult okString(Map<String, String> outputs) {
        return new NodeResult(true, toTyped(outputs), List.of(), List.of());
    }

    public static NodeResult fail(String message) {
        return new NodeResult(false, Map.of("error", TypedValue.scalar(message)), List.of(), List.of());
    }

    public Map<String, TypedValue> safeOutputs() {
        return outputs != null ? outputs : Collections.emptyMap();
    }

    /** 失败场景下返回错误信息；成功时为空串 */
    public String errorMessage() {
        TypedValue v = safeOutputs().get("error");
        return v != null ? v.render() : "";
    }

    private static Map<String, TypedValue> toTyped(Map<String, String> stringOutputs) {
        if (stringOutputs == null) {
            return Map.of();
        }
        Map<String, TypedValue> typed = new LinkedHashMap<>();
        stringOutputs.forEach((k, v) -> typed.put(k, TypedValue.scalar(v)));
        return typed;
    }
}
