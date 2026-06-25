package com.sunshine.orchestrator.conversation.dto;

import com.sunshine.orchestrator.conversation.entity.ChatConversationEntity;
import com.sunshine.orchestrator.conversation.entity.ChatMessageEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationDetailDto {

    private String id;
    private String title;
    private Instant createdAt;
    private Instant updatedAt;
    private String executionPreference;
    private List<MessageDto> messages;

    public static ConversationDetailDto from(ChatConversationEntity conv, List<ChatMessageEntity> messages) {
        return ConversationDetailDto.builder()
                .id(conv.getId())
                .title(conv.getTitle())
                .createdAt(conv.getCreatedAt())
                .updatedAt(conv.getUpdatedAt())
                .executionPreference(conv.getExecutionPreference())
                .messages(messages.stream().map(MessageDto::from).toList())
                .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MessageDto {
        private String id;
        private String role;
        private String content;
        private String reasoning;
        private String steps;
        private String status;
        private String intent;
        private String executionPlanId;
        /** user 消息发送时的 executionPreference */
        private String executionPreference;
        private int seq;
        private Instant createdAt;

        public static MessageDto from(ChatMessageEntity m) {
            MessageDto dto = new MessageDto();
            dto.setId(m.getId());
            dto.setRole(m.getRole());
            dto.setContent(m.getContent());
            dto.setReasoning(m.getReasoning());
            dto.setSteps(m.getSteps());
            dto.setStatus(m.getStatus());
            dto.setIntent(m.getIntent());
            dto.setExecutionPlanId(m.getExecutionPlanId());
            dto.setExecutionPreference(m.getExecutionPreference());
            dto.setSeq(m.getSeq());
            dto.setCreatedAt(m.getCreatedAt());
            return dto;
        }
    }
}
