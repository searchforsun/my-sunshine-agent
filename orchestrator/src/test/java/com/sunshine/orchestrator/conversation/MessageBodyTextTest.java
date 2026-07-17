package com.sunshine.orchestrator.conversation;

import com.sunshine.orchestrator.conversation.entity.ChatMessageEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessageBodyTextTest {

    @Test
    void resolve_fallsBackToContentBlocksWhenContentBlank() {
        ChatMessageEntity msg = new ChatMessageEntity();
        msg.setContent("");
        msg.setContentBlocks(
                "[{\"segmentId\":\"content-1\",\"afterStepId\":\"think\",\"text\":\"沙箱分析完成\"}]");
        assertThat(MessageBodyText.resolve(msg)).isEqualTo("沙箱分析完成");
    }

    @Test
    void resolve_prefersContentColumn() {
        ChatMessageEntity msg = new ChatMessageEntity();
        msg.setContent("主列正文");
        msg.setContentBlocks(
                "[{\"segmentId\":\"content-1\",\"afterStepId\":\"think\",\"text\":\"块\"}]");
        assertThat(MessageBodyText.resolve(msg)).isEqualTo("主列正文");
    }
}
