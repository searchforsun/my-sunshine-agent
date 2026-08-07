package com.sunshine.llm.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderPropertiesTest {

    @Test
    void bindsModelMetaList() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "llm.providers.deepseek.base-url", "https://api.deepseek.com",
                "llm.providers.deepseek.api-key", "k",
                "llm.providers.deepseek.models[0].name", "deepseek-v4-pro",
                "llm.providers.deepseek.models[0].context-window", "256000",
                "llm.providers.deepseek.models[0].encoding", "cl100k_base"));
        ProviderProperties props = new Binder(source)
                .bind("llm", Bindable.of(ProviderProperties.class))
                .orElseThrow(IllegalStateException::new);
        ProviderProperties.ProviderConfig ds = props.getProviders().get("deepseek");
        assertThat(ds.getModels()).hasSize(1);
        ProviderProperties.ModelMeta meta = ds.getModels().get(0);
        assertThat(meta.getName()).isEqualTo("deepseek-v4-pro");
        assertThat(meta.getContextWindow()).isEqualTo(256000);
        assertThat(meta.getEncoding()).isEqualTo("cl100k_base");
    }

    @Test
    void modelNames_returnsNamesForSupports() {
        ProviderProperties.ProviderConfig config = new ProviderProperties.ProviderConfig();
        ProviderProperties.ModelMeta m = new ProviderProperties.ModelMeta();
        m.setName("deepseek-v4-pro");
        config.setModels(List.of(m));
        assertThat(config.modelNames()).containsExactly("deepseek-v4-pro");
    }
}
