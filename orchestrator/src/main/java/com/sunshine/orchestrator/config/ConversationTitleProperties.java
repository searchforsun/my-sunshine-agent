package com.sunshine.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/**
 * 会话标题 LLM 摘要 — 首条消息时生成 ≤maxLength 字标题。
 * 模型 → ModelSceneResolver scene=title；提示词 → Catalog {@code conversation.title}。
 */
@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "agent.title")
public class ConversationTitleProperties {
    private boolean enabled = true;
    private int maxLength = 15;
}
