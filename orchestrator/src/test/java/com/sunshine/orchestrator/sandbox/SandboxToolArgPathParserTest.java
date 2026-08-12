package com.sunshine.orchestrator.sandbox;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 从流式 tool_call arguments 半截 JSON 尽早抠 path，供 write/edit 占位主行。 */
class SandboxToolArgPathParserTest {

    @Test
    void extractPath_fromCompleteArgs() {
        assertThat(SandboxToolArgPathParser.extractPath(
                "{\"path\":\"/workspace/plan.md\",\"content\":\"hello\"}"))
                .isEqualTo("/workspace/plan.md");
    }

    @Test
    void extractPath_whenOnlyPathArrivedSoFar() {
        assertThat(SandboxToolArgPathParser.extractPath(
                "{\"path\":\"/workspace/big.md\",\"content\":\""))
                .isEqualTo("/workspace/big.md");
    }

    @Test
    void extractPath_incompletePathValueReturnsNull() {
        assertThat(SandboxToolArgPathParser.extractPath("{\"path\":\"/workspace/bi"))
                .isNull();
    }

    @Test
    void extractPath_handlesEscapedQuotesInPath() {
        assertThat(SandboxToolArgPathParser.extractPath(
                "{\"path\":\"/workspace/say\\\"hi.md\",\"content\":\"x\"}"))
                .isEqualTo("/workspace/say\"hi.md");
    }

    @Test
    void extractPath_emptyOrNoPath() {
        assertThat(SandboxToolArgPathParser.extractPath("")).isNull();
        assertThat(SandboxToolArgPathParser.extractPath("{\"content\":\"only\"}")).isNull();
        assertThat(SandboxToolArgPathParser.extractPath(null)).isNull();
    }
}
