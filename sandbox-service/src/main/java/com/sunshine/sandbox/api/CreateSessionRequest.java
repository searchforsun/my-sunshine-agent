package com.sunshine.sandbox.api;

import java.util.Map;

public record CreateSessionRequest(
        String userId,
        String tenantId,
        String skillId,
        String runId,
        SandboxPolicyDto policy,
        Map<String, String> skillFiles,
        Map<String, String> workspaceFiles) {}
