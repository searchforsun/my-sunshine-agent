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
        return requireService().beforeLine();
    }

    public static String active() {
        return requireService().activeLine();
    }

    public static String after(String skillId) {
        if (skillId == null || skillId.isBlank()) {
            return "";
        }
        return requireService().afterLine(skillId.strip());
    }

    private static SkillLoadLabelService requireService() {
        if (service == null) {
            throw new IllegalStateException("SkillLoadLabelService 未 bind");
        }
        return service;
    }
}
