package com.sunshine.orchestrator.conversation;

import com.sunshine.orchestrator.config.AgentPromptProperties;

import java.util.Optional;

/**
 * 作答范围提示 — 任意请求在「当前提问」前注入（正文来自 Nacos scope-prompt）。
 */
public final class ConversationScopeHint {

    private ConversationScopeHint() {
    }

    public static Optional<String> resolve(AgentPromptProperties prompts) {
        if (prompts == null) {
            return Optional.empty();
        }
        String text = prompts.scopePromptOrEmpty();
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }
}
