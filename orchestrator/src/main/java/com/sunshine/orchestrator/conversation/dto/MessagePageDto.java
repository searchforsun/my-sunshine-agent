package com.sunshine.orchestrator.conversation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 会话消息游标分页结果：beforeSeq/limit 拉取一页，hasMore 指示更早历史是否仍存在 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessagePageDto {

    private List<ConversationDetailDto.MessageDto> messages;
    private boolean hasMore;
}
