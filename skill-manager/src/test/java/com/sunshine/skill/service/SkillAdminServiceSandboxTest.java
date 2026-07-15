package com.sunshine.skill.service;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.skill.dto.SandboxPolicy;
import com.sunshine.skill.entity.SkillVersionEntity;
import com.sunshine.skill.exception.SkillErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillAdminServiceSandboxTest {

    @Test
    void applySandboxFields_dockerWithNullPolicy_ok() {
        SkillVersionEntity ver = new SkillVersionEntity();
        SkillAdminService.applySandboxFields(ver, "docker", null);
        assertThat(ver.getSandbox()).isEqualTo("docker");
        assertThat(ver.getSandboxPolicyJson()).isNull();
    }

    @Test
    void applySandboxFields_persistsPolicyJson() {
        SkillVersionEntity ver = new SkillVersionEntity();
        SandboxPolicy policy = new SandboxPolicy(
                "docker", "sunshine-sandbox-python:3.11-slim", 30, 256, 0.5, List.of(), List.of("pwd"));
        SkillAdminService.applySandboxFields(ver, "docker", policy);
        assertThat(ver.getSandbox()).isEqualTo("docker");
        assertThat(ver.getSandboxPolicyJson()).contains("timeoutSec");
    }

    @Test
    void applySandboxFields_rejectsInvalidSandbox() {
        SkillVersionEntity ver = new SkillVersionEntity();
        assertThatThrownBy(() -> SkillAdminService.applySandboxFields(ver, "kvm", null))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(SkillErrorCode.SANDBOX_VALUE_INVALID));
    }
}
