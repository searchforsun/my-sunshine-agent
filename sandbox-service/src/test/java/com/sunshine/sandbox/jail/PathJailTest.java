package com.sunshine.sandbox.jail;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PathJailTest {
    @Test
    void resolvesUnderSkillsAndWorkspace() {
        assertThat(PathJail.resolveRead("/skills/demo/scripts/a.py").toString())
                .isEqualTo("/skills/demo/scripts/a.py");
        assertThat(PathJail.resolveWrite("/workspace/out.txt").toString())
                .isEqualTo("/workspace/out.txt");
    }

    @Test
    void rejectsEscapeAndSkillsWrite() {
        assertThatThrownBy(() -> PathJail.resolveRead("/skills/../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PathJail.resolveWrite("/skills/demo/x.py"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PathJail.resolveRead("/tmp/x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PathJail.resolveRead("/skill/scripts/a.py"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path escapes jail");
    }

    @Test
    void resolveCwdDefaultsWorkspace() {
        assertThat(PathJail.resolveCwd(null).toString()).isEqualTo("/workspace");
        assertThat(PathJail.resolveCwd("/skills/demo/scripts").toString()).isEqualTo("/skills/demo/scripts");
    }
}
