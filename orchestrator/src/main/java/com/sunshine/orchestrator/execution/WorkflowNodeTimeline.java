package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.ProcessingTimelineSupport;

import java.util.List;

/**
 * Workflow 节点级 Timeline step（node-{id}），复用同一 session 保证耗时与顺序正确。
 */
public final class WorkflowNodeTimeline {

    private WorkflowNodeTimeline() {
    }

    public static List<StreamToken> planStep(ProcessingTimelineSession session, WorkflowDefinition def) {
        String detail = WorkflowNodeLabels.planChain(def);
        long startedAt = System.currentTimeMillis();
        return ProcessingTimelineSupport.run(session, () -> {
            session.pending("plan", "plan");
            session.startAt("plan", "plan", startedAt);
            session.completeAt("plan", detail, System.currentTimeMillis());
        });
    }

    public static List<StreamToken> start(
            ProcessingTimelineSession session, String nodeId, String nodeType) {
        String stepId = stepId(nodeId);
        long startedAt = System.currentTimeMillis();
        return ProcessingTimelineSupport.run(session, () -> {
            session.pending(stepId, "node");
            session.startAt(stepId, "node", startedAt);
        });
    }

    public static List<StreamToken> complete(
            ProcessingTimelineSession session,
            String nodeId,
            String nodeType,
            String summaryLine,
            long startedAt,
            long endedAt) {
        return complete(session, nodeId, nodeType, summaryLine, summaryLine, startedAt, endedAt);
    }

    public static List<StreamToken> complete(
            ProcessingTimelineSession session,
            String nodeId,
            String nodeType,
            String summaryLine,
            String expandDetail,
            long startedAt,
            long endedAt) {
        String stepId = stepId(nodeId);
        return ProcessingTimelineSupport.run(session, () -> {
            if (!session.hasStep(stepId)) {
                session.pending(stepId, "node");
                session.startAt(stepId, "node", startedAt);
            }
            session.completeAt(stepId, summaryLine, expandDetail, endedAt);
        });
    }

    public static String stepId(String nodeId) {
        return "node-" + nodeId;
    }
}
