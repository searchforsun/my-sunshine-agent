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
        return start(session, nodeId, nodeType, null);
    }

    public static List<StreamToken> start(
            ProcessingTimelineSession session, String nodeId, String nodeType, String displayName) {
        String stepId = stepId(nodeId);
        long startedAt = System.currentTimeMillis();
        return ProcessingTimelineSupport.run(session, () -> {
            if (displayName != null && !displayName.isBlank()) {
                session.bindStepDisplayName(stepId, displayName.strip());
            }
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

    public static List<StreamToken> fail(
            ProcessingTimelineSession session,
            String nodeId,
            String nodeType,
            String summaryLine,
            long startedAt,
            long endedAt) {
        String stepId = stepId(nodeId);
        return ProcessingTimelineSupport.run(session, () -> {
            if (!session.hasStep(stepId)) {
                session.pending(stepId, "node");
                session.startAt(stepId, "node", startedAt);
            }
            session.fail(stepId, summaryLine);
        });
    }

    /** 用户选择重试后重新进入 running */
    /** 用户暂停 — 节点进入 paused 态 */
    public static List<StreamToken> pause(
            ProcessingTimelineSession session,
            String nodeId,
            String displayName) {
        String stepId = stepId(nodeId);
        return ProcessingTimelineSupport.run(session, () -> {
            if (displayName != null && !displayName.isBlank()) {
                session.bindStepDisplayName(stepId, displayName.strip());
            }
            if (!session.hasStep(stepId)) {
                session.pending(stepId, "node");
                session.start(stepId, "node");
            }
            session.pause(stepId, "已暂停");
        });
    }

    public static List<StreamToken> restart(
            ProcessingTimelineSession session,
            String nodeId,
            String nodeType,
            String displayName) {
        String stepId = stepId(nodeId);
        long startedAt = System.currentTimeMillis();
        return ProcessingTimelineSupport.run(session, () -> {
            if (displayName != null && !displayName.isBlank()) {
                session.bindStepDisplayName(stepId, displayName.strip());
            }
            session.pending(stepId, "node");
            session.startAt(stepId, "node", startedAt);
        });
    }

    public static String stepId(String nodeId) {
        return "node-" + nodeId;
    }
}
