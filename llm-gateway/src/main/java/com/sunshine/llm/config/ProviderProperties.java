package com.sunshine.llm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * LLM 厂商配置（映射 application.yml 中 llm.providers.*）
 */
@Data
@Component
@ConfigurationProperties(prefix = "llm")
public class ProviderProperties {

    private Map<String, ProviderConfig> providers;

    @Data
    public static class ProviderConfig {
        /** API 地址 */
        private String baseUrl;
        /** API Key */
        private String apiKey;
        /** 支持的模型列表（带上下文窗口元信息） */
        private List<ModelMeta> models;

        /** 模型名列表（供 Adapter.supports 判断）。 */
        public List<String> modelNames() {
            return models != null
                    ? models.stream().map(ModelMeta::getName).toList()
                    : List.of();
        }
    }

    @Data
    public static class ModelMeta {
        private String name;
        private int contextWindow;
        /** tokenizer 编码名，默认 cl100k_base。 */
        private String encoding = "cl100k_base";
    }
}
