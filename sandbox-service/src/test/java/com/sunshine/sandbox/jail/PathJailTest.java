package com.sunshine.sandbox.jail;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PathJailTest {
    @Test
    void resolvesUnderSkillAndWorkspace() {
        assertThat(PathJail.resolveRead("/skill/scripts/a.py").toString())
                .isEqualTo("/skill/scripts/a.py");
        assertThat(PathJail.resolveWrite("/workspace/out.txt").toString())
                .isEqualTo("/workspace/out.txt");
    }

    @Test
    void rejectsEscapeAndSkillWrite() {
        assertThatThrownBy(() -> PathJail.resolveRead("/skill/../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PathJail.resolveWrite("/skill/x.py"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PathJail.resolveRead("/tmp/x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolveCwdDefaultsWorkspace() {
        assertThat(PathJail.resolveCwd(null).toString()).isEqualTo("/workspace");
        assertThat(PathJail.resolveCwd("/skill/scripts").toString()).isEqualTo("/skill/scripts");
    }
}
