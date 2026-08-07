package com.sunshine.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/**
 * 会话标题 LLM 摘要 — 首条消息时用小模型生成 ≤maxLength 字标题。
 * 提示词正文 → Catalog {@code conversation.title}。
 */
@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "agent.title")
public class ConversationTitleProperties {
    private boolean enabled = true;
    private String model = "deepseek-v4-flash";
    private int maxLength = 15;
}
