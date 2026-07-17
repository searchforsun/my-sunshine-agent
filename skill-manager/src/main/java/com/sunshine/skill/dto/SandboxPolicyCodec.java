package com.sunshine.skill.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.sandbox.SandboxPolicy;
import com.sunshine.skill.exception.SkillErrorCode;
import org.springframework.util.StringUtils;

import java.io.IOException;

/** sandbox_policy_json ↔ SandboxPolicy */
public final class SandboxPolicyCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SandboxPolicyCodec() {
    }

    public static SandboxPolicy parse(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return MAPPER.readValue(json.strip(), SandboxPolicy.class);
        } catch (IOException e) {
            throw new BizException(SkillErrorCode.SANDBOX_POLICY_INVALID);
        }
    }

    /** Catalog 读路径：坏数据不阻断列表，视为无策略 */
    public static SandboxPolicy parseOrNull(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return MAPPER.readValue(json.strip(), SandboxPolicy.class);
        } catch (IOException e) {
            return null;
        }
    }

    public static String write(SandboxPolicy policy) {
        if (policy == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(policy);
        } catch (IOException e) {
            throw new BizException(SkillErrorCode.SANDBOX_POLICY_INVALID);
        }
    }
}
