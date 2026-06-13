package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.client.StreamToken;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class ProcessingTimelineSupport {

    private ProcessingTimelineSupport() {
    }

    public static List<StreamToken> run(ProcessingTimelineSession session, Runnable action) {
        List<ProcessingStep> emitted = new ArrayList<>();
        Consumer<ProcessingStep> prev = session.currentListener();
        session.onStepChanged(s -> {
            emitted.add(s);
            prev.accept(s);
        });
        try {
            action.run();
        } finally {
            session.onStepChanged(prev);
        }
        return emitted.stream().map(StreamToken::step).toList();
    }

    public static ProcessingTimelineSession newSession() {
        return new ProcessingTimelineSession();
    }
}
