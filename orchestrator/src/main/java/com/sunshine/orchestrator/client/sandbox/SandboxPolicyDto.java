package com.sunshine.orchestrator.client.sandbox;

import java.util.List;

/** 与 sandbox-service SandboxPolicyDto 字段对齐 */
public record SandboxPolicyDto(
        String runtime,
        String image,
        Integer timeoutSec,
        Integer memoryMb,
        Double cpus,
        List<String> networkAllow,
        List<String> execReadonlyAllow) {
}
