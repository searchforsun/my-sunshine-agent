package com.sunshine.orchestrator.taskboard;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.agent.StepEventBridge;
import com.sunshine.orchestrator.audit.AuditEvent;
import com.sunshine.orchestrator.audit.AuditPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** TaskBoard 变更事件 + 终态 MySQL 快照 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskBoardAuditService {

    private final AuditPublisher auditPublisher;
    private final TaskBoardRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void persistFinal(TaskBoardState state) {
        if (state == null || state.assistantMsgId() == null || state.assistantMsgId().isBlank()) {
            return;
        }
        try {
            StepEventBridge.ToolAuditContext ctx = StepEventBridge.toolAuditContext(state.assistantMsgId());
            Instant now = Instant.now();
            TaskBoardEntity entity = repository.findByMessageId(state.assistantMsgId()).orElseGet(() -> {
                TaskBoardEntity created = new TaskBoardEntity();
                created.setId(state.boardId());
                created.setMessageId(state.assistantMsgId());
                created.setCreatedAt(now);
                return created;
            });
            entity.setId(state.boardId());
            entity.setConversationId(ctx != null && StringUtils.hasText(ctx.conversationId())
                    ? ctx.conversationId() : "");
            entity.setUserId(ctx != null && StringUtils.hasText(ctx.userId()) ? ctx.userId() : "");
            entity.setTenantId(ctx != null && StringUtils.hasText(ctx.tenantId()) ? ctx.tenantId() : "default");
            entity.setRevision(state.revision());
            entity.setItemsJson(objectMapper.writeValueAsString(state.items()));
            entity.setUpdatedAt(now);
            repository.save(entity);
            publishEvent("react.taskboard.final", state, "ok");
        } catch (Exception e) {
            log.warn("[TaskBoardAudit] 终态落库失败 msg={}: {}", state.assistantMsgId(), e.getMessage());
        }
    }

    public Optional<TaskBoardAuditView> findByMessageId(String messageId) {
        if (!StringUtils.hasText(messageId)) {
            return Optional.empty();
        }
        return repository.findByMessageId(messageId.strip()).map(this::toView);
    }

    /** 按会话取最近一次任务板快照（T0 任务进度可观测）。 */
    public Optional<TaskBoardAuditView> findLatestByConversationId(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return Optional.empty();
        }
        return repository.findFirstByConversationIdOrderByUpdatedAtDesc(conversationId.strip()).map(this::toView);
    }

    private TaskBoardAuditView toView(TaskBoardEntity entity) {
        try {
            List<TaskBoardItemView> items = objectMapper.readValue(
                    entity.getItemsJson(), new TypeReference<>() {});
            return new TaskBoardAuditView(
                    entity.getId(),
                    entity.getMessageId(),
                    entity.getConversationId(),
                    entity.getTenantId(),
                    entity.getUserId(),
                    entity.getRevision(),
                    items,
                    entity.getCreatedAt(),
                    entity.getUpdatedAt());
        } catch (Exception e) {
            log.warn("[TaskBoardAudit] items 解析失败 msg={}: {}", entity.getMessageId(), e.getMessage());
            return new TaskBoardAuditView(
                    entity.getId(),
                    entity.getMessageId(),
                    entity.getConversationId(),
                    entity.getTenantId(),
                    entity.getUserId(),
                    entity.getRevision(),
                    List.of(),
                    entity.getCreatedAt(),
                    entity.getUpdatedAt());
        }
    }

    private void publishEvent(String eventType, TaskBoardState state, String status) {
        try {
            StepEventBridge.ToolAuditContext ctx = StepEventBridge.toolAuditContext(state.assistantMsgId());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("boardId", state.boardId());
            payload.put("revision", state.revision());
            payload.put("items", state.items());
            payload.put("summary", TaskBoardService.progressSummary(state.items()));
            String payloadJson = objectMapper.writeValueAsString(payload);
            auditPublisher.publish(new AuditEvent(
                    UUID.randomUUID().toString().replace("-", ""),
                    ctx != null && StringUtils.hasText(ctx.conversationId()) ? ctx.conversationId() : "",
                    state.assistantMsgId(),
                    ctx != null && StringUtils.hasText(ctx.userId()) ? ctx.userId() : "",
                    ctx != null && StringUtils.hasText(ctx.tenantId()) ? ctx.tenantId() : "default",
                    eventType,
                    status,
                    null,
                    payloadJson.length(),
                    payloadJson,
                    Instant.now()));
        } catch (Exception e) {
            log.warn("[TaskBoardAudit] 事件构建失败 type={}: {}", eventType, e.getMessage());
        }
    }
}
