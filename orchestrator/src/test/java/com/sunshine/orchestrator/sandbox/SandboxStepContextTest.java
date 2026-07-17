package com.sunshine.orchestrator.sandbox;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxStepContextTest {

    @Test
    void enrichInput_infersGlobSearchRootFromResults() {
        String raw = "/skills/sandbox-coding-demo/SKILL.md\n/skills/sandbox-coding-demo/scripts/hello.py\n";
        Map<String, Object> enriched = SandboxStepContext.enrichInput(
                SandboxIds.GLOB, Map.of("pattern", "**/*"), raw);
        assertThat(enriched.get("path")).isEqualTo("/skills");
    }

    @Test
    void metadata_setsSandboxPathForRead() {
        var meta = SandboxStepContext.metadata(
                SandboxIds.READ,
                Map.of("path", "/skills/demo/scripts/hello.py"),
                "hello.py");
        assertThat(meta).isNotNull();
        assertThat(meta.sandboxPath()).isEqualTo("/skills/demo/scripts/hello.py");
    }

    @Test
    void metadata_setsSearchRootForGlob() {
        var meta = SandboxStepContext.metadata(
                SandboxIds.GLOB,
                Map.of("pattern", "**/*", "path", "/skills"),
                "**/* · /skills");
        assertThat(meta).isNotNull();
        assertThat(meta.sandboxSearchRoot()).isEqualTo("/skills");
    }
}
