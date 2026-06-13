package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.agent.ProcessingStep;

import java.util.List;

/** @deprecated 使用 {@link StepSummarizer} */
@Deprecated
public final class AgentStepSummarizer {

    private AgentStepSummarizer() {
    }

    public static String afterFor(ProcessingTimelineSession session) {
        return afterFor(session, null, null);
    }

    public static String afterFor(ProcessingTimelineSession session, String ragDetailHint) {
        return afterFor(session, ragDetailHint, null);
    }

    public static String afterFor(
            ProcessingTimelineSession session, String ragDetailHint, String userQuery) {
        String detail = ragDetailHint;
        if (detail == null && session != null) {
            detail = session.snapshot().stream()
                    .filter(s -> "rag".equals(s.id()))
                    .map(ProcessingStep::detail)
                    .filter(d -> d != null && !d.isBlank())
                    .findFirst()
                    .orElse(null);
        }
        return StepSummarizer.agentAfter(userQuery, detail);
    }
}
