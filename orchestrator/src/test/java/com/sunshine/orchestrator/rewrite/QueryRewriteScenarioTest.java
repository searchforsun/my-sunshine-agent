package com.sunshine.orchestrator.rewrite;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QueryRewriteScenarioTest {

    @Test
    void ofResolvesKnownScenarios() {
        assertThat(QueryRewriteScenario.of("intent")).contains(QueryRewriteScenario.INTENT);
        assertThat(QueryRewriteScenario.of("empty-recall")).contains(QueryRewriteScenario.EMPTY_RECALL);
        assertThat(QueryRewriteScenario.of("unknown")).isEmpty();
    }

    @Test
    void isRagRelated() {
        assertThat(QueryRewriteScenario.isRagRelated("rag")).isTrue();
        assertThat(QueryRewriteScenario.isRagRelated("hyde")).isTrue();
        assertThat(QueryRewriteScenario.isRagRelated("empty-recall")).isTrue();
        assertThat(QueryRewriteScenario.isRagRelated("intent")).isFalse();
    }
}
