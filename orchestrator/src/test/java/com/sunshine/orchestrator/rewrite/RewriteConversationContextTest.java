package com.sunshine.orchestrator.rewrite;

import com.sunshine.orchestrator.conversation.ChatTurn;
import com.sunshine.orchestrator.memory.MemoryContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RewriteConversationContextTest {

    @Test
    void buildUserMessage_withoutMemory() {
        assertThat(RewriteConversationContext.buildUserMessage("待审批", null))
                .isEqualTo("用户输入：待审批");
    }

    @Test
    void buildUserMessage_withStmTurns() {
        MemoryContext memory = new MemoryContext(
                "",
                "",
                java.util.List.of(
                        new ChatTurn("user", "查待审批报销"),
                        new ChatTurn("assistant", "共有3条待审批")));
        String user = RewriteConversationContext.buildUserMessage("那第一条", memory);
        assertThat(user).contains("近期对话：");
        assertThat(user).contains("用户：查待审批报销");
        assertThat(user).contains("助手：共有3条待审批");
        assertThat(user).endsWith("用户输入：那第一条");
    }
}
