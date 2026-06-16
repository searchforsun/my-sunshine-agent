package com.sunshine.llm.adapter;

import com.sunshine.llm.config.LlmWebClientFactory;
import com.sunshine.llm.config.ProviderProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QwenAdapterTest {

    private QwenAdapter adapter;

    @BeforeEach
    void setUp() {
        ProviderProperties props = new ProviderProperties();
        ProviderProperties.ProviderConfig qwen = new ProviderProperties.ProviderConfig();
        qwen.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        qwen.setApiKey("test-key");
        qwen.setModels(List.of("qwen-plus", "qwen-turbo"));
        props.setProviders(Map.of("qwen", qwen));
        adapter = new QwenAdapter(props, new LlmWebClientFactory());
    }

    @Test
    @DisplayName("supports(qwen-plus) → true")
    void supports_qwenPlus_returnsTrue() {
        assertThat(adapter.supports("qwen-plus")).isTrue();
    }

    @Test
    @DisplayName("supports(deepseek-v4-pro) → false")
    void supports_deepseekModel_returnsFalse() {
        assertThat(adapter.supports("deepseek-v4-pro")).isFalse();
    }
}
