package com.sunshine.orchestrator.peer;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.processing.StepSummary;
import com.sunshine.orchestrator.processing.TimelineStepId;

import java.util.List;

/** 主 Timeline 压缩 peer-collab 步 */
public final class PeerTimelineSupport {

    private PeerTimelineSupport() {
    }

    public static StreamToken running(PeerTemplate template) {
        long ts = System.currentTimeMillis();
        StepSummary summary = new StepSummary(
                PeerStepLabels.before(),
                PeerStepLabels.active(template.displayName()),
                null);
        ProcessingStep step = new ProcessingStep(
                TimelineStepId.PEER_COLLAB.id(),
                TimelineStepId.PEER_COLLAB.phase(),
                "running",
                summary,
                ts, null, null,
                "templateId=" + template.id(),
                null, null, null,
                ts, PeerStepLabels.label(),
                null, null, null);
        return StreamToken.step(step);
    }

    public static StreamToken complete(PeerTemplate template, List<PeerTranscriptEntry> transcript) {
        long ts = System.currentTimeMillis();
        int rounds = transcript.stream().mapToInt(PeerTranscriptEntry::round).max().orElse(0);
        int roles = template.peerRoles().size();
        String after = PeerStepLabels.after(roles, rounds);
        StepSummary summary = new StepSummary(
                PeerStepLabels.before(),
                PeerStepLabels.active(template.displayName()),
                after);
        ProcessingStep step = new ProcessingStep(
                TimelineStepId.PEER_COLLAB.id(),
                TimelineStepId.PEER_COLLAB.phase(),
                "done",
                summary,
                ts, ts, 0L,
                "peerRunId=" + template.id() + " rounds=" + rounds,
                null, null, null,
                ts, PeerStepLabels.label(),
                null, null, null);
        return StreamToken.step(step);
    }
}
