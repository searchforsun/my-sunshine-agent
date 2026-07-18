package com.sunshine.orchestrator.plan;

import org.springframework.util.StringUtils;

/**
 * Plan 校验失败 → Replan 结构化反馈（SSOT：错误码 + 问题 + 修正指引）。
 * 供 WorkflowPlanner 注入 user message，避免模型仅见短句无法自修。
 */
public final class PlanValidationFeedback {

    private PlanValidationFeedback() {
    }

    /** Replan user message 中的 {{error}} 替换内容 */
    public static String formatForReplan(PlanValidationIssue issue) {
        if (issue == null || !StringUtils.hasText(issue.problem())) {
            PlanValidationIssue unknown = PlanValidationIssue.of(
                    PlanValidationCode.UNKNOWN, "未知校验错误");
            return format(unknown);
        }
        return format(issue);
    }

    private static String format(PlanValidationIssue issue) {
        return """
                【错误码】%s
                【问题】%s
                【修正指引】
                %s""".formatted(issue.code().name(), issue.problem(), issue.fixHint());
    }
}
