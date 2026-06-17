package com.sunshine.orchestrator.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.conversation.MessageStatus;
import com.sunshine.orchestrator.conversation.entity.ChatConversationEntity;
import com.sunshine.orchestrator.conversation.entity.ChatMessageEntity;
import com.sunshine.orchestrator.conversation.repo.ChatConversationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private static final Set<String> TERMINAL = Set.of(
            MessageStatus.COMPLETED,
            MessageStatus.FAILED,
            MessageStatus.INTERRUPTED);

    private final AuditPublisher auditPublisher;
    private final ChatConversationRepository conversationRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void auditAssistantMessage(ChatMessageEntity message) {
        if (message == null || !"assistant".equals(message.getRole())) {
            return;
        }
        if (!TERMINAL.contains(message.getStatus())) {
            return;
        }
        ChatConversationEntity conv = conversationRepository.findById(message.getConversationId())
                .orElse(null);
        if (conv == null) {
            return;
        }
        try {
            String payloadJson = objectMapper.writeValueAsString(Map.of(
                    "contentLen", message.getContent() != null ? message.getContent().length() : 0,
                    "hasReasoning", message.getReasoning() != null && !message.getReasoning().isBlank(),
                    "hasSteps", message.getSteps() != null && !message.getSteps().isBlank()));
            AuditEvent event = new AuditEvent(
                    UUID.randomUUID().toString().replace("-", ""),
                    message.getConversationId(),
                    message.getId(),
                    conv.getUserId(),
                    conv.getTenantId(),
                    "chat.message.completed",
                    message.getStatus(),
                    message.getIntent(),
                    message.getContent() != null ? message.getContent().length() : 0,
                    payloadJson,
                    Instant.now());
            auditPublisher.publish(event);
        } catch (Exception e) {
            log.warn("[Audit] 构建事件失败 msgId={}: {}", message.getId(), e.getMessage());
        }
    }
}
