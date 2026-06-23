package com.sunshine.skill.storage;

import com.sunshine.skill.dto.SkillFileEntry;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillPackageExporterTest {

    @Test
    void toZip_packagesFilesWithRelativePaths() throws Exception {
        List<SkillFileEntry> files = List.of(
                new SkillFileEntry("SKILL.md", 10, false),
                new SkillFileEntry("scripts/run.py", 20, false));
        Map<String, byte[]> contents = Map.of(
                "SKILL.md", "# skill".getBytes(StandardCharsets.UTF_8),
                "scripts/run.py", "print(1)".getBytes(StandardCharsets.UTF_8));
        byte[] zip = SkillPackageExporter.toZip(files, contents::get);
        assertTrue(zip.length > 0);
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            var entry = zis.getNextEntry();
            assertEquals("SKILL.md", entry.getName());
            assertEquals("# skill", new String(zis.readAllBytes(), StandardCharsets.UTF_8));
            entry = zis.getNextEntry();
            assertEquals("scripts/run.py", entry.getName());
            assertEquals("print(1)", new String(zis.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
