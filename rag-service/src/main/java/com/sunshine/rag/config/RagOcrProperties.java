package com.sunshine.rag.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "rag.ocr")
public class RagOcrProperties {

    private boolean enabled = true;
    private String apiKey = "";
    private String baseUrl = "https://dashscope.aliyuncs.com/api/v1";
    private String model = "qwen-vl-ocr-2025-11-20";
    /** 文本层低于该字符数则走 OCR */
    private int minTextChars = 80;
    private int maxPages = 20;
    private int renderDpi = 150;
    /** 单页 OCR HTTP 超时（秒） */
    private int timeoutSeconds = 120;
}
