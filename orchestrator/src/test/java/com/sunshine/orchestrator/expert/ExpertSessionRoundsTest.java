package com.sunshine.orchestrator.expert;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExpertSessionRoundsTest {

    @Test
    void clampSessionMax_respectsMinAndGlobalMax() {
        assertThat(ExpertSessionRounds.clampSessionMax(3, 1, 3)).isEqualTo(3);
        assertThat(ExpertSessionRounds.clampSessionMax(1, 1, 3)).isEqualTo(1);
        assertThat(ExpertSessionRounds.clampSessionMax(5, 1, 3)).isEqualTo(3);
        assertThat(ExpertSessionRounds.clampSessionMax(1, 2, 3)).isEqualTo(2);
        assertThat(ExpertSessionRounds.clampSessionMax(null, 1, 3)).isEqualTo(3);
    }
}
