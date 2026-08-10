package com.sunshine.llm.controller;

import com.sunshine.llm.config.LlmWebClientFactory;
import com.sunshine.llm.registry.GatewayModelCatalog;
import com.sunshine.llm.registry.ModelCapabilities;
import com.sunshine.llm.registry.ModelCryptoService;
import com.sunshine.llm.registry.ModelDefinitionView;
import com.sunshine.llm.registry.ModelProviderView;
import com.sunshine.llm.registry.ModelRegistryCache;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelControllerTest {

    @Test
    void listModels_fromRegistryCache() {
        ObjectMapper mapper = new ObjectMapper();
        ModelCryptoService crypto = new ModelCryptoService("sunshine-model-aes-key-32b!!");
        LlmWebClientFactory factory = new LlmWebClientFactory();
        WebClient.Builder builder = mock(WebClient.Builder.class);
        when(builder.baseUrl(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(WebClient.builder().build());
        ModelRegistryCache cache = new ModelRegistryCache(
                mapper, crypto, factory, builder,
                "http://sunshine-resource-manager", "default");
        cache.replaceSnapshotForTest(GatewayModelCatalog.builder()
                .providers(List.of(
                        ModelProviderView.builder()
                                .providerKey("deepseek").enabled(true)
                                .baseUrl("https://api.deepseek.com")
                                .apiKeyEnc(crypto.encrypt("k")).build()))
                .definitions(List.of(
                        ModelDefinitionView.builder()
                                .providerKey("deepseek")
                                .modelName("deepseek-v4-pro")
                                .displayName("DeepSeek V4 Pro")
                                .contextWindow(256000)
                                .encoding("cl100k_base")
                                .userSelectable(true)
                                .enabled(true)
                                .sortOrder(10)
                                .capabilities(ModelCapabilities.builder()
                                        .reasoning(true).toolCall(true).build())
                                .build(),
                        ModelDefinitionView.builder()
                                .providerKey("qwen")
                                .modelName("qwen-plus")
                                .displayName("Qwen Plus")
                                .contextWindow(262144)
                                .encoding("cl100k_base")
                                .userSelectable(true)
                                .enabled(true)
                                .sortOrder(30)
                                .capabilities(ModelCapabilities.builder().toolCall(true).build())
                                .build()))
                .build());

        ModelController controller = new ModelController(cache);
        var resp = controller.listModels();
        assertThat(resp.getObject()).isEqualTo("list");
        assertThat(resp.getData()).hasSize(2);
        assertThat(resp.getData())
                .anySatisfy(d -> {
                    assertThat(d.getId()).isEqualTo("deepseek-v4-pro");
                    assertThat(d.getDisplayName()).isEqualTo("DeepSeek V4 Pro");
                    assertThat(d.getContextWindow()).isEqualTo(256000);
                    assertThat(d.getProvider()).isEqualTo("deepseek");
                    assertThat(d.getCapabilities()).containsEntry("reasoning", true);
                    assertThat(d.getUserSelectable()).isTrue();
                })
                .anySatisfy(d -> {
                    assertThat(d.getId()).isEqualTo("qwen-plus");
                    assertThat(d.getContextWindow()).isEqualTo(262144);
                });
    }
}
