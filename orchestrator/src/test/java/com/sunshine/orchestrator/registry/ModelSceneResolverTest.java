package com.sunshine.orchestrator.registry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelSceneResolverTest {

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
                        def("deepseek-v4-pro", 256000, true),
                        def("deepseek-v4-flash", 128000, true),
                        def("qwen-plus", 131072, true),
                        def("disabled-model", 8000, false)),
                List.of(
                        scene("default", "deepseek-v4-pro", "qwen-plus"),
                        scene("chat", "deepseek-v4-pro", "qwen-plus"),
                        scene("intent", "deepseek-v4-flash", "qwen-plus"),
                        scene("subagent", "deepseek-v4-flash", "qwen-plus")));
    }

    @Test
    void resolve_overrideBeatsScenePrimary() {
        ResolvedModelScene r = resolver.resolve("intent", "qwen-plus");
        assertThat(r.effectiveModel()).isEqualTo("qwen-plus");
        // override 与 scene.fallback 同名时清掉，避免无意义自降级
        assertThat(r.fallbackModel()).isNull();
        assertThat(r.contextWindow()).isEqualTo(131072);
        assertThat(r.overrideInvalid()).isFalse();
    }

    @Test
    void resolve_overrideKeepsSceneFallbackWhenDifferent() {
        ResolvedModelScene r = resolver.resolve("intent", "deepseek-v4-pro");
        assertThat(r.effectiveModel()).isEqualTo("deepseek-v4-pro");
        assertThat(r.fallbackModel()).isEqualTo("qwen-plus");
    }

    @Test
    void resolve_scenePrimaryWhenNoOverride() {
        ResolvedModelScene r = resolver.resolve("intent", null);
        assertThat(r.effectiveModel()).isEqualTo("deepseek-v4-flash");
        assertThat(r.fallbackModel()).isEqualTo("qwen-plus");
        assertThat(r.contextWindow()).isEqualTo(128000);
    }

    @Test
    void resolve_fallsBackToDefaultScene() {
        ResolvedModelScene r = resolver.resolve("title", null);
        assertThat(r.effectiveModel()).isEqualTo("deepseek-v4-pro");
        assertThat(r.fallbackModel()).isEqualTo("qwen-plus");
    }

    @Test
    void resolve_invalidOverrideFallsThroughToScene() {
        ResolvedModelScene r = resolver.resolve("chat", "disabled-model");
        assertThat(r.effectiveModel()).isEqualTo("deepseek-v4-pro");
        assertThat(r.overrideInvalid()).isFalse();
    }

    @Test
    void resolveChat_marksWarningWhenOverrideInvalid() {
        ResolvedModelScene r = resolver.resolveChat("disabled-model");
        assertThat(r.effectiveModel()).isEqualTo("deepseek-v4-pro");
        assertThat(r.overrideInvalid()).isTrue();
    }

    @Test
    void resolveChat_usesValidOverride() {
        // 会话所选模型有效即生效，优先于场景主模型
        ResolvedModelScene r = resolver.resolveChat("deepseek-v4-flash");
        assertThat(r.effectiveModel()).isEqualTo("deepseek-v4-flash");
        assertThat(r.fallbackModel()).isEqualTo("qwen-plus");
        assertThat(r.overrideInvalid()).isFalse();
    }

    @Test
    void resolve_chatOverrideBeatsScenePrimary_agentConfigStillWins() {
        ResolvedModelScene sessionWins = resolver.resolve("chat", null, "deepseek-v4-flash");
        assertThat(sessionWins.effectiveModel()).isEqualTo("deepseek-v4-flash");

        ResolvedModelScene agentWins = resolver.resolve("chat", "qwen-plus", "deepseek-v4-flash");
        assertThat(agentWins.effectiveModel()).isEqualTo("qwen-plus");
    }

    @Test
    void resolve_failFastWhenNoScenes() {
        resolver.replaceSnapshotForTest(
                List.of(def("only", 1000, true)),
                List.of());
        assertThatThrownBy(() -> resolver.resolve("chat", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refuse Nacos fallback");
    }

    @Test
    void resolve_surfacesModelRequestExtrasAndLetsSceneOverride() {
        resolver.replaceSnapshotForTest(
                List.of(
                        defWithExtras("deepseek-v4-pro", 500000, Map.of("max_completion_tokens", 128000, "temperature", 0.3)),
                        defWithExtras("qwen-plus", 131072, Map.of())),
                List.of(new ModelCatalogScene("chat", "deepseek-v4-pro", "qwen-plus",
                        Map.of("temperature", 0.9), true)));

        ResolvedModelScene r = resolver.resolve("chat", null);

        // 模型级输出预算必须透出，否则 ReAct 退回全局 max-tokens 会把正文截断成空
        assertThat(r.extras()).containsEntry("max_completion_tokens", 128000);
        // 场景 extras 覆盖同名键
        assertThat(r.extras()).containsEntry("temperature", 0.9);
    }

    private static ModelCatalogDefinition def(String name, int window, boolean enabled) {
        return new ModelCatalogDefinition(
                name, "p", name, window, 8192, "cl100k_base",
                ModelCapabilities.defaults(), null, true, enabled, 0);
    }

    private static ModelCatalogDefinition defWithExtras(String name, int window, Map<String, Object> requestExtras) {
        return new ModelCatalogDefinition(
                name, "p", name, window, 8192, "cl100k_base",
                ModelCapabilities.defaults(), requestExtras, true, true, 0);
    }

    private static ModelCatalogScene scene(String key, String primary, String fallback) {
        return new ModelCatalogScene(key, primary, fallback, Map.of(), true);
    }
}
