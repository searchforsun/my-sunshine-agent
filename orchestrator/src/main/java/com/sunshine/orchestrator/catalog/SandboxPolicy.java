package com.sunshine.orchestrator.catalog;

import java.util.List;

/** Skill 沙箱策略 — 与 skill-manager Catalog / sandbox-service 字段对齐 */
public record SandboxPolicy(
        String runtime,
        String image,
        Integer timeoutSec,
        Integer memoryMb,
        Double cpus,
        List<String> networkAllow,
        List<String> execReadonlyAllow
) {
}
