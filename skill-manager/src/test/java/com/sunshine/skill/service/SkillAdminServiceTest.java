package com.sunshine.skill.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SkillAdminServiceTest {

    @Test
    void listPrefixedPaths_filtersReferencesAndScripts() {
        Map<String, byte[]> files = Map.of(
                "references/a.md", new byte[0],
                "scripts/run.py", new byte[0],
                "SKILL.md", new byte[0]);
        assertThat(SkillAdminService.listPrefixedPaths(files, "references/"))
                .containsExactly("references/a.md");
        assertThat(SkillAdminService.listPrefixedPaths(files, "scripts/"))
                .containsExactly("scripts/run.py");
    }
}
