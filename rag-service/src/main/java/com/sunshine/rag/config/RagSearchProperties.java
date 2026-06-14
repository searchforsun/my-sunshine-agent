package com.sunshine.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 检索参数
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.search")
public class RagSearchProperties {

    /**
     * Milvus IP 相似度下限（text-embedding 归一化向量下近似 cosine）。
     * 低于此值的片段视为弱相关，不计入命中。
     */
    private float minScore = 0.48f;
}
