package com.sunshine.orchestrator.conversation;

import com.sunshine.orchestrator.config.AgentPromptProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationScopeHintTest {

    @Test
    void resolve_withPrompt_alwaysReturnsScope() {
        AgentPromptProperties props = new AgentPromptProperties();
        props.setScopePrompt("scope text");
        assertThat(ConversationScopeHint.resolve(props)).contains("scope text");
    }

    @Test
    void resolve_blankScope_empty() {
        AgentPromptProperties props = new AgentPromptProperties();
        props.setScopePrompt("  ");
        assertThat(ConversationScopeHint.resolve(props)).isEmpty();
    }

    @Test
    void resolve_nullPrompts_empty() {
        assertThat(ConversationScopeHint.resolve(null)).isEmpty();
    }
}
