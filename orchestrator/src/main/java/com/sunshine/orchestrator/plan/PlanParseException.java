package com.sunshine.orchestrator.plan;

/** Planner 输出无法解析或校验失败 */
public class PlanParseException extends RuntimeException {
    public PlanParseException(String message) {
        super(message);
    }
}
