package com.sunshine.llm.router;

import com.sunshine.llm.adapter.DeepSeekAdapter;
import com.sunshine.llm.adapter.LlmAdapter;
import com.sunshine.llm.adapter.QwenAdapter;
import com.sunshine.llm.config.LlmWebClientFactory;
import com.sunshine.llm.config.ProviderProperties;
import com.sunshine.llm.model.ChatCompletionRequest;
import com.sunshine.llm.model.ChatCompletionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelRouterTest {

    private ProviderProperties props;

    @BeforeEach
    void setUp() {
        props = new ProviderProperties();

        ProviderProperties.ProviderConfig deepseek = new ProviderProperties.ProviderConfig();
        deepseek.setBaseUrl("https://api.deepseek.com");
        deepseek.setApiKey("ds-key");
        deepseek.setModels(List.of("deepseek-v4-pro"));

        ProviderProperties.ProviderConfig qwen = new ProviderProperties.ProviderConfig();
        qwen.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        qwen.setApiKey("qw-key");
        qwen.setModels(List.of("qwen-plus"));

        props.setProviders(Map.of("deepseek", deepseek, "qwen", qwen));
    }

    @Test
    @DisplayName("init 注册 DeepSeekAdapter 与 QwenAdapter")
    void init_registersBothAdapters() {
        LlmWebClientFactory factory = new LlmWebClientFactory();
        List<LlmAdapter> adapters = List.of(new DeepSeekAdapter(props, factory), new QwenAdapter(props, factory));
        ModelRouter router = new ModelRouter(adapters);
        router.init();

        assertThat(adapters).hasSize(2);
        assertThat(adapters).anyMatch(QwenAdapter.class::isInstance);
        assertThat(adapters).anyMatch(a -> a.supports("qwen-plus"));
        assertThat(adapters).anyMatch(a -> a.supports("deepseek-v4-pro"));
    }

    @Test
    @DisplayName("route(qwen-plus) 选中 QwenAdapter")
    void route_qwenPlus_selectsQwenAdapter() {
        LlmAdapter deepseek = mock(LlmAdapter.class);
        LlmAdapter qwen = mock(LlmAdapter.class);
        when(deepseek.supports("qwen-plus")).thenReturn(false);
        when(qwen.supports("qwen-plus")).thenReturn(true);
        when(qwen.chat(any())).thenReturn(Mono.empty());

        ModelRouter router = new ModelRouter(List.of(deepseek, qwen));

        ChatCompletionRequest request = new ChatCompletionRequest();
        request.setModel("qwen-plus");

        router.route(request).block();

        verify(qwen).chat(request);
        verify(deepseek, never()).chat(any());
    }

    @Test
    @DisplayName("route(unknown) 抛出 IllegalArgumentException")
    void route_unknownModel_throws() {
        LlmAdapter deepseek = mock(LlmAdapter.class);
        LlmAdapter qwen = mock(LlmAdapter.class);
        when(deepseek.supports("unknown-model")).thenReturn(false);
        when(qwen.supports("unknown-model")).thenReturn(false);

        ModelRouter router = new ModelRouter(List.of(deepseek, qwen));

        ChatCompletionRequest request = new ChatCompletionRequest();
        request.setModel("unknown-model");

        assertThatThrownBy(() -> router.route(request).block())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown-model");
    }
}
