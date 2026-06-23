package com.sunshine.skill.skillmd;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillPackageImporterTest {

    @Test
    void fromMarkdown_wrapsDocument() {
        String raw = """
                ---
                name: policy-review
                description: 制度审查
                ---

                # 制度审查
                """;
        SkillPackage pkg = SkillPackageImporter.fromMarkdown(raw);
        assertThat(pkg.document().name()).isEqualTo("policy-review");
        assertThat(pkg.files()).isEmpty();
    }

    @Test
    void fromZip_extractsSkillMdAndAssets() throws Exception {
        byte[] zip = buildZip();
        SkillPackage pkg = SkillPackageImporter.fromZip(zip);
        assertThat(pkg.document().name()).isEqualTo("finance-analysis");
        assertThat(pkg.files()).containsKeys("SKILL.md", "references/guide.md", "scripts/run.py");
    }

    @Test
    void fromZip_requiresSkillMd() {
        assertThatThrownBy(() -> SkillPackageImporter.fromZip(new byte[0]))
                .isInstanceOf(Exception.class);
    }

    private static byte[] buildZip() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            putEntry(zos, "SKILL.md", """
                    ---
                    name: finance-analysis
                    description: d
                    ---

                    # body
                    """);
            putEntry(zos, "references/guide.md", "guide");
            putEntry(zos, "scripts/run.py", "print('ok')");
        }
        return baos.toByteArray();
    }

    private static void putEntry(ZipOutputStream zos, String name, String content) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }
}
