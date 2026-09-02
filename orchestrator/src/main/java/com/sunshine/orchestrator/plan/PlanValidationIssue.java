package com.sunshine.orchestrator.plan;

/** 结构化 Plan 校验问题；null 表示通过。 */
public record PlanValidationIssue(PlanValidationCode code, String problem, String fixHint) {

    public static PlanValidationIssue of(PlanValidationCode code, String problem) {
        return new PlanValidationIssue(code, problem, code.defaultFixHint());
    }

    public static PlanValidationIssue of(PlanValidationCode code, String problem, String fixHint) {
        return new PlanValidationIssue(code, problem, fixHint);
    }

    /** 供日志与落库使用的简短问题描述 */
    public String message() {
        return problem;
    }
}
