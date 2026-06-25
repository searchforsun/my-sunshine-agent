package com.sunshine.orchestrator.execution.agent;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.ProcessingStepMerger;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.execution.WorkflowNodeTimeline;
import com.sunshine.orchestrator.processing.StepSummary;

import java.util.ArrayList;
import java.util.List;

/** 子 Agent 步骤 → 挂到 node-{id}.subSteps，主 Timeline 仍仅 node 一步 */
public final class SubAgentTimelineBridge {

    private final String nodeStepId;
    private final String nodeLabel;
    private final List<ProcessingStep> subSteps = new ArrayList<>();

    public SubAgentTimelineBridge(String nodeId, String nodeLabel) {
        this.nodeStepId = WorkflowNodeTimeline.stepId(nodeId);
        this.nodeLabel = nodeLabel != null && !nodeLabel.isBlank() ? nodeLabel.strip() : nodeId;
    }

    public List<StreamToken> wrap(StreamToken token) {
        if (token == null) {
            return List.of();
        }
        if (token.isStep() && token.step() != null) {
            ProcessingStepMerger.upsert(subSteps, token.step());
            return List.of(nodeStepUpdate());
        }
        if (token.isStepDelta()) {
            ProcessingStepMerger.applyDelta(subSteps, token.stepId(), token.channel(), token.text());
            return List.of(nodeStepUpdate());
        }
        return List.of();
    }

    public List<ProcessingStep> subSteps() {
        return List.copyOf(subSteps);
    }

    private StreamToken nodeStepUpdate() {
        long ts = System.currentTimeMillis();
        ProcessingStep node = new ProcessingStep(
                nodeStepId,
                "node",
                "running",
                new StepSummary(null, nodeLabel, null),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                ts,
                "running",
                nodeLabel,
                null,
                List.copyOf(subSteps));
        return StreamToken.step(node);
    }
}
