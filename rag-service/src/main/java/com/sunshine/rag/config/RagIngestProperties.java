package com.sunshine.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "rag.ingest")
public class RagIngestProperties {
    /** OCR/解析置信度低于此值进入 quarantine */
    private double confidenceThreshold = 0.65;
    private boolean quarantineEnabled = true;
}
