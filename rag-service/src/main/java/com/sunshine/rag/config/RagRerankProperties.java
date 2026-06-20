package com.sunshine.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "rag.rerank")
public class RagRerankProperties {

    private boolean enabled = true;
    private String apiKey = "";
    private String baseUrl =
            "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";
    private String model = "gte-rerank";
    private int inputSize = 20;
    private int timeoutSeconds = 30;
    /** Rerank relevance 下限（与向量 min-score 量纲不同） */
    private float minScore = 0.25f;
    /** 低于此 relevance 的候选直接丢弃（避免映射后误过 0.48 向量门禁） */
    private float minRelevance = 0.25f;
}
