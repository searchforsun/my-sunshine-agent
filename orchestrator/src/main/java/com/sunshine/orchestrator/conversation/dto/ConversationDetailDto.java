package com.sunshine.orchestrator.conversation.dto;

import com.sunshine.orchestrator.conversation.ConversationService;
import com.sunshine.orchestrator.conversation.entity.ChatConversationEntity;
import com.sunshine.orchestrator.conversation.entity.ChatMessageEntity;
import com.sunshine.orchestrator.routing.ExecutionMode;
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
    private String kbId;
    private String modelName;
    private String reasoningEffort;
    /** chat / task；与库表 SSOT 一致，勿在详情响应中省略 */
    private String kind;
    private String workspaceId;
    private String checkoutPath;
    private List<MessageDto> messages;
    /** 是否仍有更早历史：详情只含最近消息窗口，更早历史由前端经 /messages 游标分页拉取 */
    private boolean hasMore;

    /** DTO 列表版：分页查询结果直接组装（MessagePageDto 已持有 MessageDto） */
    public static ConversationDetailDto of(ChatConversationEntity conv, List<MessageDto> messages, boolean hasMore) {
        return ConversationDetailDto.builder()
                .id(conv.getId())
                .title(conv.getTitle())
                .createdAt(conv.getCreatedAt())
                .updatedAt(conv.getUpdatedAt())
                .executionPreference(ExecutionMode.toStoredWire(conv.getExecutionPreference()))
                .kbId(conv.getKbId())
                .modelName(conv.getModelName())
                .reasoningEffort(conv.getReasoningEffort())
                .kind(conv.getKind())
                .workspaceId(conv.getWorkspaceId())
                .checkoutPath(conv.getCheckoutPath())
                .messages(messages)
                .hasMore(hasMore)
                .build();
    }

    public static ConversationDetailDto from(ChatConversationEntity conv, List<ChatMessageEntity> messages, boolean hasMore) {
        return of(conv, messages.stream().map(MessageDto::from).toList(), hasMore);
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MessageDto {
        private String id;
        private String role;
        private String content;
        private String reasoning;
        private String steps;
        private String contentBlocks;
        /** 消息级 LLM usage + 上下文分组快照 JSON（前端刷新恢复 usage 的数据源） */
        private String usage;
        private String status;
        private String intent;
        private String executionPlanId;
        /** user 消息发送时的执行模式（值域 fast|pro|workflow；DTO 字段名沿用读侧旧名 executionPreference） */
        private String executionPreference;
        /** 图片 URL 列表（image_urls_json 反序列化；无图返回空列表） */
        private java.util.List<String> imageUrls;
        private int seq;
        private Instant createdAt;
        private Instant updatedAt;

        public static MessageDto from(ChatMessageEntity m) {
            MessageDto dto = new MessageDto();
            dto.setId(m.getId());
            dto.setRole(m.getRole());
            dto.setContent(m.getContent());
            dto.setReasoning(m.getReasoning());
            dto.setSteps(m.getSteps());
            dto.setContentBlocks(m.getContentBlocks());
            dto.setUsage(m.getUsageJson());
            dto.setStatus(m.getStatus());
            dto.setIntent(m.getIntent());
            dto.setExecutionPlanId(m.getExecutionPlanId());
            dto.setExecutionPreference(ExecutionMode.toStoredWire(m.getExecutionPreference()));
            dto.setImageUrls(ConversationService.readImageUrls(m.getImageUrlsJson()));
            dto.setSeq(m.getSeq());
            dto.setCreatedAt(m.getCreatedAt());
            dto.setUpdatedAt(m.getUpdatedAt());
            return dto;
        }
    }
}
