package com.sunshine.rag.admin.config;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigBundlePathUtilsTest {

    @Test
    void setAndGetNestedPath() {
        Map<String, Object> root = new LinkedHashMap<>();
        ConfigBundlePathUtils.setPath(root, "search.minScore", 0.42);
        ConfigBundlePathUtils.setPath(root, "rewrite.rag.systemPrompt", "new prompt");
        assertThat(ConfigBundlePathUtils.getPath(root, "search.minScore")).isEqualTo(0.42);
        assertThat(ConfigBundlePathUtils.getPath(root, "rewrite.rag.systemPrompt")).isEqualTo("new prompt");
    }
}
