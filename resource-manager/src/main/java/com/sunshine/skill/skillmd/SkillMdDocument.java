package com.sunshine.skill.skillmd;

import java.util.List;

/** 解析后的标准 SKILL.md — 官方 frontmatter（name、description、tools）+ Markdown 正文 */
public record SkillMdDocument(
        String name,
        String description,
        List<String> tools,
        String body
) {
    public SkillMdDocument {
        tools = tools != null ? List.copyOf(tools) : List.of();
    }
}
