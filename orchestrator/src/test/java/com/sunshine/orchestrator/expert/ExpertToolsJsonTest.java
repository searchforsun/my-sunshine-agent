package com.sunshine.orchestrator.expert;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExpertToolsJsonTest {

    @Test
    void nullOrBlank_returnsEmpty() {
        assertThat(ExpertToolsJson.parse(null)).isEmpty();
        assertThat(ExpertToolsJson.parse("")).isEmpty();
        assertThat(ExpertToolsJson.parse("   ")).isEmpty();
    }

    @Test
    void emptyArray_returnsEmpty() {
        assertThat(ExpertToolsJson.parse("[]")).isEmpty();
    }

    @Test
    void concreteIds_preservedInOrder() {
        assertThat(ExpertToolsJson.parse("[\"sdk__a__t1\",\"mcp__b__t2\"]"))
                .containsExactly("sdk__a__t1", "mcp__b__t2");
    }

    @Test
    void starAlone_isStarSentinel() {
        assertThat(ExpertToolsJson.isStarAll(ExpertToolsJson.parse("[\"*\"]"))).isTrue();
        assertThat(ExpertToolsJson.isStarAll(List.of("sdk__a__t1"))).isFalse();
        assertThat(ExpertToolsJson.isStarAll(List.of())).isFalse();
    }

    @Test
    void invalidJson_returnsEmpty() {
        assertThat(ExpertToolsJson.parse("not-json")).isEmpty();
    }
}
