package com.sunshine.orchestrator.client;

/** 非流式 LLM 补全结果 — content 为正文，reasoning 为思考链（若模型返回） */
public record LlmCompletion(String content, String reasoning) {

    public String contentOrEmpty() {
        return content != null ? content : "";
    }

    public String reasoningOrEmpty() {
        return reasoning != null ? reasoning : "";
    }
}
