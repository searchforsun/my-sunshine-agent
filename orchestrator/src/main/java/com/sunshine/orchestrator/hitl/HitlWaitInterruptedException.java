package com.sunshine.orchestrator.hitl;

/** 用户暂停/断连中断 HITL 等待（非明确拒绝）— 续跑应 re-await */
public class HitlWaitInterruptedException extends RuntimeException {

    public HitlWaitInterruptedException() {
        super("HITL wait interrupted");
    }
}
