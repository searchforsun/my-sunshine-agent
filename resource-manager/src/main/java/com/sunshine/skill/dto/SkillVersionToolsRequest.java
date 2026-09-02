package com.sunshine.skill.dto;

import java.util.List;

/** 更新版本 tools_json（UI 覆写入口，独立于 SKILL.md frontmatter） */
public record SkillVersionToolsRequest(
        List<String> tools
) {
}
