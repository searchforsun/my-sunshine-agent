package com.sunshine.orchestrator.context;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModelWindowCacheTest {

    private ContextProperties properties;
    private ModelWindowCache cache;

    @BeforeEach
    void setUp() {
        properties = new ContextProperties();
        properties.getL1().setDefaultModelWindow(128000);
        cache = new ModelWindowCache(properties);
    }

    @Test
    void windowFor_knownModel_returnsCached() {
        cache.refresh(Map.of("deepseek-v4-pro", 128000, "qwen-plus", 131072));
        assertThat(cache.windowFor("deepseek-v4-pro")).isEqualTo(128000);
        assertThat(cache.windowFor("qwen-plus")).isEqualTo(131072);
    }

    @Test
    void windowFor_unknownModel_returnsDefault() {
        cache.refresh(Map.of("deepseek-v4-pro", 128000));
        assertThat(cache.windowFor("unknown-model")).isEqualTo(128000);
    }

    @Test
    void windowFor_noRefresh_returnsDefault() {
        assertThat(cache.windowFor("deepseek-v4-pro")).isEqualTo(128000);
    }

    @Test
    void refresh_replacesStaleEntries() {
        cache.refresh(Map.of("m1", 100));
        cache.refresh(Map.of("m2", 200));
        assertThat(cache.windowFor("m1")).isEqualTo(128000);
        assertThat(cache.windowFor("m2")).isEqualTo(200);
    }
}
