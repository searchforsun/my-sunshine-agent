package com.sunshine.orchestrator.skill;

/** Skill 绑定解析结果 */
public record SkillBindingOutcome(
        String skillId,
        String effectiveQuery,
        SkillBindingSource source,
        String unknownToken
) {
    public static final String PARAM_SKILL = "skill";
    public static final String PARAM_EFFECTIVE_QUERY = "effectiveQuery";
    /** 5B Skill 驱动 Plan：Planner 读 L2 正文生成 DAG */
    public static final String PARAM_PLANNER_MODE = "plannerMode";
    public static final String PLANNER_MODE_SKILL_DRIVEN = "skill-driven";

    public boolean bound() {
        return skillId != null && !skillId.isBlank();
    }

    public boolean unknown() {
        return unknownToken != null && !unknownToken.isBlank();
    }

    public static SkillBindingOutcome none(String query) {
        return new SkillBindingOutcome(null, query, null, null);
    }

    public static SkillBindingOutcome bound(String skillId, String effectiveQuery, SkillBindingSource source) {
        return new SkillBindingOutcome(skillId, effectiveQuery, source, null);
    }

    public static SkillBindingOutcome unknown(String token) {
        return new SkillBindingOutcome(null, null, null, token);
    }
}
