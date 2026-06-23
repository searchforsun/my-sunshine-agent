package com.sunshine.skill.skillmd;

/** 解析后的标准 SKILL.md — 仅官方 frontmatter（name、description）+ Markdown 正文 */
public record SkillMdDocument(
        String name,
        String description,
        String body
) {
}
