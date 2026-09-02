package com.sunshine.skill.dto;

/** Skill 包内文件条目 — 相对版本根目录路径 */
public record SkillFileEntry(
        String path,
        long size,
        boolean directory
) {
}
