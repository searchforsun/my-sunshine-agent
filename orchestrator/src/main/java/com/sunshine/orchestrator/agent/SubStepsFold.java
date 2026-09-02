package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.client.StreamToken;

import java.util.ArrayList;
import java.util.List;

/**
 * 子步骤折叠原语：step / step_delta → subSteps。
 * spawn 与 Workflow agent Bridge 共用，禁止再各写一份 upsert/applyDelta。
 */
public final class SubStepsFold {

    private final List<ProcessingStep> subSteps = new ArrayList<>();

    /** @return true 若已消费为 step 或 step_delta */
    public boolean ingest(StreamToken token) {
        if (token == null) {
            return false;
        }
        if (token.isStep() && token.step() != null) {
            ProcessingStepMerger.upsert(subSteps, token.step());
            return true;
        }
        if (token.isStepDelta()) {
            ProcessingStepMerger.applyDelta(subSteps, token.stepId(), token.channel(), token.text());
            return true;
        }
        return false;
    }

    public List<ProcessingStep> snapshot() {
        return List.copyOf(subSteps);
    }

    /** HITL 扫描等只读遍历 */
    public List<ProcessingStep> view() {
        return subSteps;
    }
}
