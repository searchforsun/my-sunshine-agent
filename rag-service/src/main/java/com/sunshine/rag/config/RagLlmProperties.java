package com.sunshine.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** llm-gateway 非流式补全（Query 改写） */
@Data
@Component
@ConfigurationProperties(prefix = "rag.llm")
public class RagLlmProperties {
    private String baseUrl = "http://127.0.0.1:8300/v1";
    private String apiKey = "";
    private String defaultModel = "deepseek-v4-flash";
}
