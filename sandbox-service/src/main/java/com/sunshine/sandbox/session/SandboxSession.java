package com.sunshine.sandbox.session;

import com.sunshine.common.sandbox.SandboxPolicy;

import java.nio.file.Path;

public record SandboxSession(
        String sessionId,
        String containerName,
        Path hostRoot,
        SandboxPolicy policy) {}
