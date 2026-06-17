package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.ProcessingTimelineSupport;
import com.sunshine.orchestrator.processing.StepLabels;

import java.util.ArrayList;
import java.util.List;

/**
 * Workflow 节点级 Timeline step（node-{id}）
 */
public final class WorkflowNodeTimeline {

    private WorkflowNodeTimeline() {
    }

    public static List<StreamToken> start(String nodeId, String nodeType) {
        String stepId = stepId(nodeId);
        ProcessingTimelineSession session = ProcessingTimelineSupport.newSession();
        return ProcessingTimelineSupport.run(session, () -> {
            session.pending(stepId, "node");
            session.start(stepId, label(nodeId, nodeType));
        });
    }

    public static List<StreamToken> complete(String nodeId, String nodeType, String detail) {
        String stepId = stepId(nodeId);
        ProcessingTimelineSession session = ProcessingTimelineSupport.newSession();
        return ProcessingTimelineSupport.run(session, () -> {
            if (!session.hasStep(stepId)) {
                session.pending(stepId, "node");
                session.start(stepId, label(nodeId, nodeType));
            }
            session.complete(stepId, detail != null ? detail : label(nodeId, nodeType) + "完成");
        });
    }

    public static String stepId(String nodeId) {
        return "node-" + nodeId;
    }

    private static String label(String nodeId, String nodeType) {
        return StepLabels.labelFor("node-" + nodeId) + " (" + nodeType + ")";
    }

    public static List<StreamToken> planStep(List<String> nodeIds) {
        ProcessingTimelineSession session = ProcessingTimelineSupport.newSession();
        String detail = String.join(" → ", nodeIds);
        return ProcessingTimelineSupport.run(session, () -> {
            session.pending("plan", "plan");
            session.start("plan", "plan");
            session.complete("plan", detail);
        });
    }
}
