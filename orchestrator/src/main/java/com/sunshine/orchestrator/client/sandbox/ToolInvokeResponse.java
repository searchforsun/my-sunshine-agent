package com.sunshine.orchestrator.client.sandbox;

import java.util.Map;

/** 沙箱工具调用结果 — 对齐 sandbox-service ToolInvokeResponse */
public record ToolInvokeResponse(
        boolean ok,
        String output,
        Integer exitCode,
        Map<String, Object> meta) {
}
