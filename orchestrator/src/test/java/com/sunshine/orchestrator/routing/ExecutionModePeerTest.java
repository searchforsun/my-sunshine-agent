package com.sunshine.orchestrator.routing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionModePeerTest {

    @Test
    void fromPeerCollabWireLabel() {
        assertThat(ExecutionMode.from("peer-collab")).isEqualTo(ExecutionMode.PEER_COLLAB);
        assertThat(ExecutionMode.from("peer_collab")).isEqualTo(ExecutionMode.PEER_COLLAB);
    }

    @Test
    void intentLabelIncludesPeerCollab() {
        ExecutionPlan plan = new ExecutionPlan(ExecutionMode.PEER_COLLAB, null, java.util.Map.of(), "test");
        assertThat(plan.intentLabel()).isEqualTo("peer-collab");
    }
}
