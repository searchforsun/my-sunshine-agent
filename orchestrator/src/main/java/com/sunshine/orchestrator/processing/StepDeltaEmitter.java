package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.client.StreamToken;

/**
 * 步骤级流式增量 — SSE type:step_delta
 */
public final class StepDeltaEmitter {

    private StepDeltaEmitter() {
    }

    public static StreamToken emit(ProcessingTimelineSession session, String channel, String text) {
        String stepId = session.activeStepId();
        if (stepId == null || text == null || text.isEmpty()) {
            return null;
        }
        session.appendDelta(channel, text);
        return StreamToken.stepDelta(stepId, channel, text);
    }
}
