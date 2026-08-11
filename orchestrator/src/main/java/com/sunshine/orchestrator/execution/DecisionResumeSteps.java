package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.agent.ProcessingStep;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ReactExecutor → ReActAgentRuntime：续跑 steps 短时传递（bridge bind 后 DecisionResumeSupport 消费）。
 */
public final class DecisionResumeSteps {

    private static final ConcurrentHashMap<String, List<ProcessingStep>> BY_MESSAGE = new ConcurrentHashMap<>();

    private DecisionResumeSteps() {
    }

    public static void bind(String messageId, List<ProcessingStep> steps) {
        if (messageId == null || messageId.isBlank() || steps == null || steps.isEmpty()) {
            return;
        }
        BY_MESSAGE.put(messageId.strip(), List.copyOf(steps));
    }

    public static List<ProcessingStep> take(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return List.of();
        }
        List<ProcessingStep> steps = BY_MESSAGE.remove(messageId.strip());
        return steps != null ? steps : List.of();
    }
}
