package com.sunshine.rag.admin.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigBundlePayloadTest {

    @Test
    void parseRewrite_readsHydeFromRewriteLevel() {
        RewriteSettings settings = ConfigBundlePayload.parseRewrite(ConfigBundleTestFixtures.fullPayload());
        assertThat(settings.rag().hyde().systemPrompt()).isEqualTo("hyde-prompt");
    }

    @Test
    void parseRewrite_rejectsNestedHydeUnderRag() {
        Map<String, Object> payload = ConfigBundleTestFixtures.fullPayload();
        @SuppressWarnings("unchecked")
        Map<String, Object> rewrite = new java.util.LinkedHashMap<>((Map<String, Object>) payload.get("rewrite"));
        @SuppressWarnings("unchecked")
        Map<String, Object> rag = new java.util.LinkedHashMap<>((Map<String, Object>) rewrite.get("rag"));
        rag.put("hyde", Map.of("enabled", true, "model", "m", "maxChars", 480, "systemPrompt", "bad"));
        rewrite.put("rag", rag);
        payload.put("rewrite", rewrite);
        assertThatThrownBy(() -> ConfigBundlePayload.parseRewrite(payload))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rewrite.hyde");
    }

    @Test
    void requireRetrieval_failsWhenSearchMissing() {
        assertThatThrownBy(() -> ConfigBundlePayload.requireRetrieval(Map.of()))
                .isInstanceOf(IllegalStateException.class);
    }
}
