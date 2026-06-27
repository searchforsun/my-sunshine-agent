package com.sunshine.orchestrator.plan;

/** 用户暂停 generation，Plan 确认/执行应安静退出（checkpoint 已由 GenerationJob.cancel 落库） */
public class PlanWorkflowPausedException extends RuntimeException {

    public PlanWorkflowPausedException() {
        super("plan workflow paused");
    }
}
