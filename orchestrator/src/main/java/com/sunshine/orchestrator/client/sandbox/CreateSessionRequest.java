package com.sunshine.orchestrator.client.sandbox;

import java.util.Map;

/** 创建沙箱会话 — 对齐 sandbox-service CreateSessionRequest */
public record CreateSessionRequest(
        String userId,
        String tenantId,
        String skillId,
        String runId,
        SandboxPolicyDto policy,
        Map<String, String> skillFiles,
        Map<String, String> workspaceFiles) {
}
