package com.sunshine.common.sandbox;

import java.util.Map;

public record CreateSessionRequest(
        String userId,
        String tenantId,
        String skillId,
        String runId,
        SandboxPolicy policy,
        Map<String, String> skillFiles,
        Map<String, String> workspaceFiles) {
}
