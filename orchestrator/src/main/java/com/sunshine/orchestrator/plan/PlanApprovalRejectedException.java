package com.sunshine.orchestrator.plan;

/** 用户未确认或超过重新生成次数 */
public class PlanApprovalRejectedException extends RuntimeException {

    public PlanApprovalRejectedException(String message) {
        super(message);
    }
}
