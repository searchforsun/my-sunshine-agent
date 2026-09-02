package com.sunshine.orchestrator.conversation.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class ConversationSearchDto {

    private String id;
    private String title;
    /** chat / task */
    private String kind;
    private String workspaceId;
    private Instant createdAt;
    private Instant updatedAt;
    /** 命中消息正文摘要（仅按消息内容命中时有值） */
    private String snippet;
}
