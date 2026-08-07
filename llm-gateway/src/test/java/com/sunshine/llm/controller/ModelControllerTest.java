package com.sunshine.llm.controller;

import com.sunshine.llm.config.ProviderProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModelControllerTest {

    @Test
    void listModels_aggregatesAllProviders() {
        ProviderProperties props = new ProviderProperties();
        ProviderProperties.ProviderConfig ds = new ProviderProperties.ProviderConfig();
        ProviderProperties.ModelMeta m1 = new ProviderProperties.ModelMeta();
        m1.setName("deepseek-v4-pro");
        m1.setContextWindow(256000);
        m1.setEncoding("cl100k_base");
        ds.setModels(List.of(m1));
        ProviderProperties.ProviderConfig qw = new ProviderProperties.ProviderConfig();
        ProviderProperties.ModelMeta m2 = new ProviderProperties.ModelMeta();
        m2.setName("qwen-plus");
        m2.setContextWindow(262144);
        m2.setEncoding("cl100k_base");
        qw.setModels(List.of(m2));
        props.setProviders(Map.of("deepseek", ds, "qwen", qw));

        ModelController controller = new ModelController(props);
        var resp = controller.listModels();
        assertThat(resp.getObject()).isEqualTo("list");
        assertThat(resp.getData()).hasSize(2);
        assertThat(resp.getData())
                .anySatisfy(d -> {
                    assertThat(d.getId()).isEqualTo("deepseek-v4-pro");
                    assertThat(d.getContextWindow()).isEqualTo(256000);
                })
                .anySatisfy(d -> {
                    assertThat(d.getId()).isEqualTo("qwen-plus");
                    assertThat(d.getContextWindow()).isEqualTo(262144);
                });
    }
}
