package com.sunshine.skill.dto;

import com.sunshine.common.sandbox.SandboxPolicy;

/** 更新版本 sandbox / sandbox_policy */
public record SkillVersionSandboxRequest(
        String sandbox,
        SandboxPolicy sandboxPolicy
) {
}
