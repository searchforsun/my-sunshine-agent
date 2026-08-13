package com.sunshine.orchestrator.conversation;

import com.sunshine.orchestrator.conversation.dto.ConversationDetailDto;
import com.sunshine.orchestrator.conversation.dto.ConversationSummaryDto;
import com.sunshine.orchestrator.conversation.entity.ChatConversationEntity;
import com.sunshine.orchestrator.conversation.entity.ChatMessageEntity;
import com.sunshine.orchestrator.routing.ExecutionPreference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 会话持久化 wire：写出 fast|pro|workflow；读侧映射旧值 */
class ConversationExecutionPreferenceWireTest {

    @Test
    @DisplayName("toStoredWire 写出新 wire，空保持 null")
    void toStoredWire_mapsLegacyAndKeepsBlankNull() {
        assertThat(ExecutionPreference.toStoredWire("auto")).isEqualTo("fast");
        assertThat(ExecutionPreference.toStoredWire("react")).isEqualTo("fast");
        assertThat(ExecutionPreference.toStoredWire("plan-workflow")).isEqualTo("pro");
        assertThat(ExecutionPreference.toStoredWire("pro")).isEqualTo("pro");
        assertThat(ExecutionPreference.toStoredWire("workflow")).isEqualTo("workflow");
        assertThat(ExecutionPreference.toStoredWire(null)).isNull();
        assertThat(ExecutionPreference.toStoredWire("  ")).isNull();
    }

    @Test
    @DisplayName("会话/消息 DTO 读侧将旧库值映射为新 wire")
    void dtoFrom_mapsLegacyStoredPreference() {
        Instant now = Instant.parse("2026-08-13T00:00:00Z");
        ChatConversationEntity conv = new ChatConversationEntity();
        conv.setId("c1");
        conv.setTitle("t");
        conv.setCreatedAt(now);
        conv.setUpdatedAt(now);
        conv.setExecutionPreference("plan-workflow");
        conv.setKind("chat");

        ChatMessageEntity user = new ChatMessageEntity();
        user.setId("m1");
        user.setRole("user");
        user.setContent("hi");
        user.setStatus("COMPLETED");
        user.setSeq(1);
        user.setExecutionPreference("react");
        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        ConversationSummaryDto summary = ConversationSummaryDto.from(conv);
        assertThat(summary.getExecutionPreference()).isEqualTo("pro");

        ConversationDetailDto detail = ConversationDetailDto.from(conv, List.of(user));
        assertThat(detail.getExecutionPreference()).isEqualTo("pro");
        assertThat(detail.getMessages()).hasSize(1);
        assertThat(detail.getMessages().get(0).getExecutionPreference()).isEqualTo("fast");
    }
}
