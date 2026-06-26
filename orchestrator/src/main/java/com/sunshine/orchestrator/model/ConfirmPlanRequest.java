package com.sunshine.orchestrator.model;

/** 动态 Plan 用户确认请求 */
public record ConfirmPlanRequest(String token, String action, String modificationHint) {
}
