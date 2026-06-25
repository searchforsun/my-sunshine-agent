package com.sunshine.orchestrator.processing;

/** Skill 加载步骤文案 — 由 {@link SkillLoadLabelService} 启动时绑定 */
public final class SkillLoadLabels {

    private static volatile SkillLoadLabelService service;

    private SkillLoadLabels() {
    }

    public static void bind(SkillLoadLabelService labelService) {
        service = labelService;
    }

    public static String before() {
        return service != null ? service.beforeLine() : "准备加载 Skill";
    }

    public static String active() {
        return service != null ? service.activeLine() : "正在加载 Skill 指令";
    }

    public static String after(String skillId) {
        if (skillId == null || skillId.isBlank()) {
            return "";
        }
        return service != null ? service.afterLine(skillId.strip()) : "@" + skillId.strip();
    }
}
