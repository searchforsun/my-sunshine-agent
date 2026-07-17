package com.sunshine.common.sandbox;

import java.util.List;

/** Skill / 会话沙箱策略 — orchestrator · skill-manager · sandbox-service SSOT */
public record SandboxPolicy(
        String runtime,
        String image,
        Integer timeoutSec,
        Integer memoryMb,
        Double cpus,
        List<String> networkAllow,
        List<String> execReadonlyAllow) {
}
