package com.sunshine.orchestrator.conversation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationContextPolicyTest {

    @Test
    void continuation_detectsExplicitKeywords() {
        assertThat(ConversationContextPolicy.isContinuationRequest("继续补充刚才的代码")).isTrue();
        assertThat(ConversationContextPolicy.isContinuationRequest("它的复杂度是多少")).isTrue();
    }

    @Test
    void continuation_newStandaloneTask_false() {
        assertThat(ConversationContextPolicy.isContinuationRequest("写一段 py 快速排序，解释其工作流程")).isFalse();
        assertThat(ConversationContextPolicy.isContinuationRequest("写一段 cpp 快速排序")).isFalse();
    }

    @Test
    void filterForLlm_standaloneTask_emptyHistory() {
        List<ChatTurn> loaded = List.of(
                new ChatTurn("user", "写 cpp 快排"),
                new ChatTurn("assistant", "cpp code..."));
        assertThat(ConversationContextPolicy.filterForLlm(loaded, "写 py 快排", 20)).isEmpty();
    }

    @Test
    void filterForLlm_continuation_keepsTail() {
        List<ChatTurn> loaded = List.of(
                new ChatTurn("user", "u1"),
                new ChatTurn("assistant", "a1"),
                new ChatTurn("user", "u2"),
                new ChatTurn("assistant", "a2"));
        List<ChatTurn> out = ConversationContextPolicy.filterForLlm(loaded, "继续补充", 4);
        assertThat(out).hasSize(4);
    }
}
