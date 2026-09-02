package com.sunshine.orchestrator.context;

import com.sunshine.orchestrator.registry.ModelCapabilities;
import com.sunshine.orchestrator.registry.ModelCatalogDefinition;
import com.sunshine.orchestrator.registry.ModelCatalogScene;
import com.sunshine.orchestrator.registry.ModelSceneResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelWindowCacheTest {

    private ModelWindowCache cache;
    private ModelSceneResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ModelSceneResolver(
                new com.fasterxml.jackson.databind.ObjectMapper(),
                org.springframework.web.reactive.function.client.WebClient.builder(),
                "http://localhost",
                "default");
        resolver.replaceSnapshotForTest(
                List.of(
                        new ModelCatalogDefinition(
                                "deepseek-v4-pro", "p", "pro", 256000, 8192, "cl100k_base",
                                ModelCapabilities.defaults(), null, true, true, 0),
                        new ModelCatalogDefinition(
                                "qwen-plus", "p", "qwen", 262144, 8192, "cl100k_base",
                                ModelCapabilities.defaults(), null, true, true, 0)),
                List.of(new ModelCatalogScene("chat", "deepseek-v4-pro", "qwen-plus", Map.of(), true)));
        cache = new ModelWindowCache(resolver);
        cache.refresh(resolver.allContextWindows());
    }

    @Test
    void windowFor_knownModel_returnsCached() {
        assertThat(cache.windowFor("deepseek-v4-pro")).isEqualTo(256000);
        assertThat(cache.windowFor("qwen-plus")).isEqualTo(262144);
    }

    @Test
    void windowFor_unknownModel_failFast() {
        assertThatThrownBy(() -> cache.windowFor("unknown-model"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refresh_replacesStaleEntries() {
        cache.refresh(Map.of("m1", 100));
        cache.refresh(Map.of("m2", 200));
        assertThat(cache.windowFor("m2")).isEqualTo(200);
        assertThatThrownBy(() -> cache.windowFor("m1"))
                .isInstanceOf(IllegalStateException.class);
    }
}
