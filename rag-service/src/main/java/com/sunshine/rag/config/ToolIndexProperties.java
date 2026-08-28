package com.sunshine.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 工具语义索引（tool RAG）参数 — Nacos rag.tool-index。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.tool-index")
public class ToolIndexProperties {

    /** 工具索引开关；关闭时检索端点返回空，orchestrator 回退 full 注入 */
    private boolean enabled = true;

    /** 工具命中相似度下限（text-embedding 归一化向量下近似 cosine）；低于该值的工具不计入 */
    private float minScore = 0.30f;

    /** 请求体未传 topK 时的默认值 */
    private int defaultTopK = 8;
}
