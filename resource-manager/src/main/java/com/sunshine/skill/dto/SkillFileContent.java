package com.sunshine.skill.dto;

/** Skill 包内单文件内容 — 文本或 base64 */
public record SkillFileContent(
        String path,
        String contentType,
        String content,
        boolean binary
) {
}
