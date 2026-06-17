package com.sunshine.orchestrator.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Agent 提示词 SSOT — 正文维护于 Nacos {@code sunshine-orchestrator.yaml}（本地副本 docs/nacos/）。
 */
@Getter
@Setter
@RefreshScope
@ConfigurationProperties(prefix = "agent")
public class AgentPromptProperties {

    /** 主系统提示词（直连 LLM + ReActAgent） */
    private String systemPrompt = "";

    /** 多轮对话时追加的作答范围提示（history ≥ 2 且非空时注入） */
    private String scopePrompt = "";

    private Intent intent = new Intent();

    public boolean hasSystemPrompt() {
        return StringUtils.hasText(systemPrompt);
    }

    public String systemPromptOrEmpty() {
        return systemPrompt != null ? systemPrompt.strip() : "";
    }

    public String scopePromptOrEmpty() {
        return scopePrompt != null ? scopePrompt.strip() : "";
    }

    @Getter
    @Setter
    public static class Intent {

        /** 意图分类模型 */
        private String model = "deepseek-v4-flash";

        /** 意图分类 system 提示词 */
        private String classifierPrompt = "";
    }

    public String intentClassifierPromptOrEmpty() {
        return intent != null && intent.classifierPrompt != null
                ? intent.classifierPrompt.strip()
                : "";
    }

    public String intentModelOrDefault() {
        if (intent == null || !StringUtils.hasText(intent.model)) {
            return "deepseek-v4-flash";
        }
        return intent.model.strip();
    }
}
