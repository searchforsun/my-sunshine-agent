package com.sunshine.skill.dto;

import java.util.Map;

/** Skill 材料快照 — 仅 scripts/ + references/ 文本，供 sandbox 挂载 */
public record SkillMaterialResponse(Map<String, String> files) {
}
