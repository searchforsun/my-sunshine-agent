package com.sunshine.skill.dto;

import java.util.List;

/** Skill 沙箱策略 — 与 design §9 / sandbox-service SandboxPolicyDto 字段对齐 */
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
