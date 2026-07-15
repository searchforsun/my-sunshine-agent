package com.sunshine.skill.dto;

public record SkillCreateRequest(
        String id,
        String displayName,
        String description,
        String sandbox,
        SandboxPolicy sandboxPolicy
) {
}
