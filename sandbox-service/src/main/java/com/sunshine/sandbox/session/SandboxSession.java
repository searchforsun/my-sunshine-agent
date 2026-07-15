package com.sunshine.sandbox.session;

import com.sunshine.sandbox.api.SandboxPolicyDto;

import java.nio.file.Path;

public record SandboxSession(
        String sessionId,
        String containerName,
        Path hostRoot,
        SandboxPolicyDto policy) {}
