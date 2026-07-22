package com.sunshine.orchestrator.rewrite;

import com.sunshine.orchestrator.conversation.ChatTurn;
import com.sunshine.orchestrator.context.AssembledContext;
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
        AssembledContext memory = new AssembledContext(
                "", "", java.util.List.of(),
                java.util.List.of(
                        new ChatTurn("user", "查待审批报销"),
                        new ChatTurn("assistant", "共有3条待审批")),
                "");
        String user = RewriteConversationContext.buildUserMessage("那第一条", memory);
        assertThat(user).contains("近期对话：");
        assertThat(user).contains("用户：查待审批报销");
        assertThat(user).contains("助手：共有3条待审批");
        assertThat(user).endsWith("用户输入：那第一条");
    }

    @Test
    void buildUserMessage_labelsL2AndFarSeparately() {
        AssembledContext memory = new AssembledContext(
                "- preference: 简洁",
                "[更早对话 · Far]\n曾讨论差旅",
                java.util.List.of(),
                java.util.List.of(),
                "");
        String user = RewriteConversationContext.buildUserMessage("继续", memory);
        assertThat(user).contains("用户状态（L2）：");
        assertThat(user).contains("- preference: 简洁");
        assertThat(user).contains("更早对话摘要（Far）：");
        assertThat(user).contains("曾讨论差旅");
        assertThat(user).doesNotContain("中期记忆摘要");
        assertThat(user).doesNotContain("长期记忆摘要");
    }
}
