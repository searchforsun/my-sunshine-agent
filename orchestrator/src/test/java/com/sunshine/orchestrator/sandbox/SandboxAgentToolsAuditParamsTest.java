package com.sunshine.orchestrator.sandbox;

import com.sunshine.common.sandbox.ToolInvokeResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxAgentToolsAuditParamsTest {

    @Test
    void digestsSensitiveFieldsAndKeepsPath() {
        Map<String, String> params = SandboxAgentTools.auditParams(
                Map.of(
                        "path", "/workspace/a.txt",
                        "content", "hello-secret",
                        "old_string", "aaa",
                        "new_string", "bbb"),
                "sess-1",
                new ToolInvokeResponse(true, "out", 0, Map.of()),
                12L);
        assertThat(params.get("path")).isEqualTo("/workspace/a.txt");
        assertThat(params.get("sessionId")).isEqualTo("sess-1");
        assertThat(params.get("exitCode")).isEqualTo("0");
        assertThat(params.get("durationMs")).isEqualTo("12");
        assertThat(params.get("content")).isEqualTo(SandboxAgentTools.sha256Hex("hello-secret"));
        assertThat(params.get("old_string")).isEqualTo(SandboxAgentTools.sha256Hex("aaa"));
        assertThat(params.get("new_string")).isEqualTo(SandboxAgentTools.sha256Hex("bbb"));
        assertThat(params.get("content")).doesNotContain("hello");
        assertThat(params.get("content")).hasSize(64);
    }
}
