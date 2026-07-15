package com.sunshine.sandbox.api;

import java.util.List;

public record SandboxPolicyDto(
        String runtime,
        String image,
        Integer timeoutSec,
        Integer memoryMb,
        Double cpus,
        List<String> networkAllow,
        List<String> execReadonlyAllow) {}
