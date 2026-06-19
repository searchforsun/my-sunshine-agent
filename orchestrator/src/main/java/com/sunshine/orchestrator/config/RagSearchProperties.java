package com.sunshine.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/** orchestrator 侧 RAG 检索参数（与 rag-service 解耦，仅 topK） */
@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "rag.search")
public class RagSearchProperties {
    private int defaultTopK = 3;
}
