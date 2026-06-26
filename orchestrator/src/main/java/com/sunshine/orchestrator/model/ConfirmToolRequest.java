package com.sunshine.orchestrator.model;

/** HITL 写工具确认请求 */
public record ConfirmToolRequest(String token, boolean approved) {
}
