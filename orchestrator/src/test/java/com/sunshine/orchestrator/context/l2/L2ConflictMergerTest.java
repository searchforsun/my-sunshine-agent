package com.sunshine.orchestrator.context.l2;

import com.sunshine.orchestrator.context.ContextProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class L2ConflictMergerTest {

    private ContextProperties.L2 l2;
    private L2ConflictMerger merger;

    @BeforeEach
    void setUp() {
        l2 = new ContextProperties.L2();
        l2.setMinConfidence(0.75);
        l2.setConstraintOverwriteConfidence(0.9);
        merger = new L2ConflictMerger();
    }

    @Test
    void preference_newerHighConfidence_supersedesOld() {
        UserContextStateEntity old = active("preference", "style", "详细", 0.8);
        L2ConflictMerger.Candidate neu = new L2ConflictMerger.Candidate(
                "preference", "style", "简洁", 0.85);

        L2ConflictMerger.Decision d = merger.decide(old, neu, l2);

        assertThat(d).isEqualTo(L2ConflictMerger.Decision.ACCEPT);
    }

    @Test
    void constraint_requiresHigherOverwriteConfidence() {
        UserContextStateEntity old = active("constraint", "budget", "单次不超过500", 0.9);
        L2ConflictMerger.Candidate neu = new L2ConflictMerger.Candidate(
                "constraint", "budget", "单次不超过800", 0.8);

        L2ConflictMerger.Decision d = merger.decide(old, neu, l2);

        assertThat(d).isEqualTo(L2ConflictMerger.Decision.REJECT);
    }

    @Test
    void fact_requiresElevatedOverwriteConfidenceLikeConstraint() {
        UserContextStateEntity old = active("fact", "company", "Sunshine", 0.9);
        L2ConflictMerger.Candidate neu = new L2ConflictMerger.Candidate(
                "fact", "company", "OtherCo", 0.85);

        assertThat(merger.decide(old, neu, l2)).isEqualTo(L2ConflictMerger.Decision.REJECT);

        L2ConflictMerger.Candidate high = new L2ConflictMerger.Candidate(
                "fact", "company", "OtherCo", 0.95);
        assertThat(merger.decide(old, high, l2)).isEqualTo(L2ConflictMerger.Decision.ACCEPT);
    }

    @Test
    void noExisting_alwaysAccept() {
        L2ConflictMerger.Candidate neu = new L2ConflictMerger.Candidate(
                "preference", "style", "简洁", 0.8);
        assertThat(merger.decide(null, neu, l2)).isEqualTo(L2ConflictMerger.Decision.ACCEPT);
    }

    private static UserContextStateEntity active(String kind, String key, String value, double conf) {
        UserContextStateEntity e = new UserContextStateEntity();
        e.setKind(kind);
        e.setStateKey(key);
        e.setStateValue(value);
        e.setConfidence(conf);
        e.setStatus("active");
        return e;
    }
}
