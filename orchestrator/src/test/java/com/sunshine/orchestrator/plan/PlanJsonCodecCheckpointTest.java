package com.sunshine.orchestrator.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlanJsonCodecCheckpointTest {

    private final PlanJsonCodec codec = new PlanJsonCodec(new ObjectMapper());

    @Test
    void legacyCheckpointDefaultsExecuting() {
        String legacy = "{\"resumeNodeId\":\"n1\",\"wfCtxJson\":\"{\\\"nodes\\\":{}}\"}";
        WorkflowCheckpoint cp = codec.checkpointFromJson(legacy);
        assertThat(cp.resumeNodeId()).isEqualTo("n1");
        assertThat(cp.pausePhase()).isEqualTo(PausePhase.EXECUTING);
        assertThat(cp.pendingInteraction()).isNull();
    }

    @Test
    void planningPhaseAllowsEmptyResumeNodeId() {
        WorkflowCheckpoint cp = new WorkflowCheckpoint("", "{}", PausePhase.PLANNING, null);
        String json = codec.checkpointToJson(cp);
        WorkflowCheckpoint roundTrip = codec.checkpointFromJson(json);
        assertThat(roundTrip.pausePhase()).isEqualTo(PausePhase.PLANNING);
        assertThat(roundTrip.resumeNodeId()).isEmpty();
    }

    @Test
    void pendingInteractionRoundTrip() {
        PendingInteraction pending = new PendingInteraction(
                "hitl", "approve_oa", null, "approve_oa_task", "taskId=1", null);
        WorkflowCheckpoint cp = new WorkflowCheckpoint("approve_oa", "{}", PausePhase.EXECUTING, pending);
        WorkflowCheckpoint roundTrip = codec.checkpointFromJson(codec.checkpointToJson(cp));
        assertThat(roundTrip.pendingInteraction()).isNotNull();
        assertThat(roundTrip.pendingInteraction().kind()).isEqualTo("hitl");
        assertThat(roundTrip.pendingInteraction().hitlToolId()).isEqualTo("approve_oa_task");
    }

    @Test
    void compactConstructorDefaultsExecuting() {
        WorkflowCheckpoint cp = new WorkflowCheckpoint("n1", "{}");
        assertThat(cp.pausePhase()).isEqualTo(PausePhase.EXECUTING);
        assertThat(cp.pendingInteraction()).isNull();
    }
}
