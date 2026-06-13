package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.client.StreamToken;

public final class ProcessingStepEmitter {

    private ProcessingStepEmitter() {
    }

    public static StreamToken step(ProcessingStep s) {
        return StreamToken.step(s);
    }
}
