package com.sunshine.skill.dto;

import com.sunshine.common.sandbox.SandboxPolicy;

public record SkillCreateRequest(
        String id,
        String displayName,
        String description,
        String sandbox,
        SandboxPolicy sandboxPolicy
) {
}
