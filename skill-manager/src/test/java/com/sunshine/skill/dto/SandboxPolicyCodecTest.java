package com.sunshine.skill.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.sandbox.SandboxPolicy;
import com.sunshine.skill.exception.SkillErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SandboxPolicyCodecTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void writeAndParse_roundTrip() throws Exception {
        SandboxPolicy policy = new SandboxPolicy(
                "docker",
                "sunshine-sandbox-python:3.11-slim",
                30,
                256,
                0.5,
                List.of(),
                List.of("ls *", "pwd"), null);
        String json = SandboxPolicyCodec.write(policy);
        assertThat(MAPPER.readTree(json).get("timeoutSec").asInt()).isEqualTo(30);
        assertThat(SandboxPolicyCodec.parse(json)).isEqualTo(policy);
    }

    @Test
    void parse_invalidJson_throwsBizException() {
        assertThatThrownBy(() -> SandboxPolicyCodec.parse("{not-json"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(SkillErrorCode.SANDBOX_POLICY_INVALID));
    }

    @Test
    void parseOrNull_blankAndCorrupt() {
        assertThat(SandboxPolicyCodec.parseOrNull(null)).isNull();
        assertThat(SandboxPolicyCodec.parseOrNull("  ")).isNull();
        assertThat(SandboxPolicyCodec.parseOrNull("{bad")).isNull();
    }

    @Test
    void catalogEntry_serializesSandboxFields() throws Exception {
        SandboxPolicy policy = new SandboxPolicy(
                "docker", "sunshine-sandbox-python:3.11-slim", 30, 256, 0.5, List.of(), List.of("pwd"), null);
        SkillCatalogEntry entry = new SkillCatalogEntry(
                "demo", "Demo", "d", "overlay", 1, true, null, null, true, "docker", policy);
        String json = MAPPER.writeValueAsString(entry);
        assertThat(json).contains("\"sandbox\":\"docker\"");
        assertThat(json).contains("\"sandboxPolicy\"");
        assertThat(json).contains("\"timeoutSec\":30");
        SkillCatalogEntry back = MAPPER.readValue(json, SkillCatalogEntry.class);
        assertThat(back.sandbox()).isEqualTo("docker");
        assertThat(back.sandboxPolicy()).isEqualTo(policy);
    }
}
