package com.sunshine.llm.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.llm.config.LlmWebClientFactory;
import com.sunshine.llm.filter.NormalizeFilter;
import com.sunshine.llm.registry.GatewayModelCatalog;
import com.sunshine.llm.registry.ModelCapabilities;
import com.sunshine.llm.registry.ModelCryptoService;
import com.sunshine.llm.registry.ModelDefinitionView;
import com.sunshine.llm.registry.ModelProviderView;
import com.sunshine.llm.registry.ModelRegistryCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenAiCompatibleAdapterTest {

    private OpenAiCompatibleAdapter adapter;
    private ModelRegistryCache registryCache;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        ModelCryptoService crypto = new ModelCryptoService("sunshine-model-aes-key-32b!!");
        String enc = crypto.encrypt("sk-test");
        LlmWebClientFactory factory = new LlmWebClientFactory();
        WebClient.Builder builder = mock(WebClient.Builder.class);
        when(builder.baseUrl(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(WebClient.builder().build());
        registryCache = new ModelRegistryCache(
                mapper, crypto, factory, builder,
                "http://sunshine-resource-manager", "default");
        registryCache.replaceSnapshotForTest(GatewayModelCatalog.builder()
                .providers(List.of(
                        ModelProviderView.builder()
                                .providerKey("qwen")
                                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
                                .pathPrefix("")
                                .apiKeyEnc(enc)
                                .enabled(true)
                                .build(),
                        ModelProviderView.builder()
                                .providerKey("deepseek")
                                .baseUrl("https://api.deepseek.com")
                                .pathPrefix("/v1")
                                .apiKeyEnc(enc)
                                .enabled(true)
                                .build()))
                .definitions(List.of(
                        ModelDefinitionView.builder()
                                .providerKey("qwen")
                                .modelName("qwen-plus")
                                .displayName("Qwen Plus")
                                .enabled(true)
                                .capabilities(ModelCapabilities.builder().toolCall(true).build())
                                .build(),
                        ModelDefinitionView.builder()
                                .providerKey("deepseek")
                                .modelName("deepseek-v4-pro")
                                .displayName("DeepSeek V4 Pro")
                                .enabled(true)
                                .capabilities(ModelCapabilities.builder()
                                        .reasoning(true).toolCall(true).build())
                                .build()))
                .build());
        adapter = new OpenAiCompatibleAdapter(
                registryCache,
                factory,
                new OpenAiRequestBodyFactory(mapper, registryCache),
                new NormalizeFilter(mapper),
                mapper);
    }

    @Test
    @DisplayName("supports(qwen-plus) → true（注册表精确匹配）")
    void supports_qwenPlus_returnsTrue() {
        assertThat(adapter.supports("qwen-plus")).isTrue();
    }

    @Test
    @DisplayName("supports(qwen-turbo) → false（禁止前缀猜测）")
    void supports_unknownPrefix_returnsFalse() {
        assertThat(adapter.supports("qwen-turbo")).isFalse();
        assertThat(adapter.supports("deepseek-chat")).isFalse();
    }

    @Test
    @DisplayName("path_prefix 归一化")
    void chatCompletionsPath_normalizes() {
        assertThat(OpenAiCompatibleAdapter.chatCompletionsPath("")).isEqualTo("/chat/completions");
        assertThat(OpenAiCompatibleAdapter.chatCompletionsPath(null)).isEqualTo("/chat/completions");
        assertThat(OpenAiCompatibleAdapter.chatCompletionsPath("/v1")).isEqualTo("/v1/chat/completions");
        assertThat(OpenAiCompatibleAdapter.chatCompletionsPath("v1")).isEqualTo("/v1/chat/completions");
        assertThat(OpenAiCompatibleAdapter.chatCompletionsPath("/v1/")).isEqualTo("/v1/chat/completions");
    }
}
