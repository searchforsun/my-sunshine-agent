package com.sunshine.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Markdown 分段参数 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.chunk")
public class RagChunkProperties {

    private int maxSize = 1200;
}
