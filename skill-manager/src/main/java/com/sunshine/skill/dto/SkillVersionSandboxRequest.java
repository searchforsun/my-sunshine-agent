package com.sunshine.skill.dto;

/** 更新版本 sandbox / sandbox_policy */
public record SkillVersionSandboxRequest(
        String sandbox,
        SandboxPolicy sandboxPolicy
) {
}
