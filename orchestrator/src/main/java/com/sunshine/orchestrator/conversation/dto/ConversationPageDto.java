package com.sunshine.orchestrator.conversation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 会话列表 offset/limit 分页：hasMore 表示是否还有下一页 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationPageDto {

    private List<ConversationSummaryDto> items;
    private boolean hasMore;
}
