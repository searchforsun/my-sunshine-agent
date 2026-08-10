package com.sunshine.orchestrator.conversation.dto;

import com.sunshine.orchestrator.conversation.entity.ChatConversationEntity;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class ConversationSummaryDto {

    private String id;
    private String title;
    private Instant createdAt;
    private Instant updatedAt;
    private String executionPreference;
    private String kbId;
    private String modelName;
    private String kind;
    private String workspaceId;
    private String checkoutPath;

    public static ConversationSummaryDto from(ChatConversationEntity conv) {
        return ConversationSummaryDto.builder()
                .id(conv.getId())
                .title(conv.getTitle())
                .createdAt(conv.getCreatedAt())
                .updatedAt(conv.getUpdatedAt())
                .executionPreference(conv.getExecutionPreference())
                .kbId(conv.getKbId())
                .modelName(conv.getModelName())
                .kind(conv.getKind())
                .workspaceId(conv.getWorkspaceId())
                .checkoutPath(conv.getCheckoutPath())
                .build();
    }
}
