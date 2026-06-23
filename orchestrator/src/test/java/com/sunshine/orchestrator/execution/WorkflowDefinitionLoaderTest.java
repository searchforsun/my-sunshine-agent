package com.sunshine.orchestrator.execution;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowDefinitionLoaderTest {

    @Test
    void isIndexedListMap_detectsSpringYamlListBinding() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("0", "list_finance_messages");
        assertThat(WorkflowDefinitionLoader.isIndexedListMap(map)).isTrue();
        assertThat(WorkflowDefinitionLoader.indexedListValues(map))
                .containsExactly("list_finance_messages");
    }

    @Test
    void isIndexedListMap_rejectsArbitraryMap() {
        Map<String, Object> map = Map.of("tool", "list_finance_messages");
        assertThat(WorkflowDefinitionLoader.isIndexedListMap(map)).isFalse();
    }
}
