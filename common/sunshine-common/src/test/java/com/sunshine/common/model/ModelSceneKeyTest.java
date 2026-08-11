package com.sunshine.common.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ModelSceneKeyTest {

    @Test
    void knownKeys_matchSqlSeedContract() {
        Set<String> keys = Arrays.stream(ModelSceneKey.values())
                .map(ModelSceneKey::key)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        assertThat(keys).containsExactly(
                "default", "chat", "intent", "planner",
                "rewrite.intent", "rewrite.planner", "title", "subagent");
        assertThat(ModelSceneKey.fromKey("chat")).contains(ModelSceneKey.CHAT);
        assertThat(ModelSceneKey.isKnown("unknown")).isFalse();
    }
}
