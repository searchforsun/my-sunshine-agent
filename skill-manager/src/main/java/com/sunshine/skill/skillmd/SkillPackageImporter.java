package com.sunshine.skill.skillmd;

import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** 从 SKILL.md 或 zip 包导入标准 Skill */
public final class SkillPackageImporter {

    private SkillPackageImporter() {
    }

    public static SkillPackage fromMarkdown(String raw) {
        String content = raw != null ? raw : "";
        SkillMdDocument doc = SkillMdParser.parse(content);
        return new SkillPackage(content, doc, Map.of());
    }

    public static SkillPackage fromZip(byte[] zipBytes) throws IOException {
        Map<String, byte[]> entries = unzip(zipBytes);
        String skillMd = findSkillMd(entries);
        if (!StringUtils.hasText(skillMd)) {
            throw new IllegalArgumentException("zip 包中未找到 SKILL.md");
        }
        SkillMdDocument doc = SkillMdParser.parse(skillMd);
        Map<String, byte[]> files = new LinkedHashMap<>(entries);
        files.put("SKILL.md", skillMd.getBytes(StandardCharsets.UTF_8));
        return new SkillPackage(skillMd, doc, Map.copyOf(files));
    }

    public static Map<String, byte[]> unzip(byte[] zipBytes) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = normalizeEntryName(entry.getName());
                entries.put(name, zis.readAllBytes());
            }
        }
        return entries;
    }

    static String findSkillMd(Map<String, byte[]> entries) {
        if (entries.containsKey("SKILL.md")) {
            return new String(entries.get("SKILL.md"), StandardCharsets.UTF_8);
        }
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            if (e.getKey().endsWith("/SKILL.md") || "skill.md".equalsIgnoreCase(e.getKey())) {
                return new String(e.getValue(), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    static String normalizeEntryName(String name) {
        String normalized = name.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }
}
