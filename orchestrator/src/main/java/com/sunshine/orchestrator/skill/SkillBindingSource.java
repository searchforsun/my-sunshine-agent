package com.sunshine.orchestrator.skill;

/** Skill 显式绑定来源 — 流程 1 / 2 */
public enum SkillBindingSource {
    AT_MENTION,
    HINT_PATTERN,
    /** 前端 catalog 识别后显式传入 skillId */
    CLIENT
}
