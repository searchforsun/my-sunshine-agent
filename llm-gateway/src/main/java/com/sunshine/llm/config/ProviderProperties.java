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
        /** 支持的模型列表 */
        private List<String> models;
    }
}
