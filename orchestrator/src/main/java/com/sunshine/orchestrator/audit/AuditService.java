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
import java.util.LinkedHashMap;
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
            StepsSummaryExtractor.Summary stepsSummary =
                    StepsSummaryExtractor.fromStepsJson(message.getSteps());
            QueryRewriteAuditExtractor.Summary rewriteSummary =
                    QueryRewriteAuditExtractor.fromStepsJson(message.getSteps());
            RoutingAuditExtractor.Summary routingSummary =
                    RoutingAuditExtractor.fromStepsJson(message.getSteps());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("contentLen", message.getContent() != null ? message.getContent().length() : 0);
            payload.put("hasReasoning", message.getReasoning() != null && !message.getReasoning().isBlank());
            payload.put("hasSteps", message.getSteps() != null && !message.getSteps().isBlank());
            payload.put("rewriteApplied", rewriteSummary.rewriteApplied());
            payload.put("rewriteLatencyMs", rewriteSummary.rewriteLatencyMs());
            if (!rewriteSummary.rewrites().isEmpty()) {
                payload.put("rewrites", rewriteSummary.rewrites());
            }
            payload.put("stepsSummary", Map.of(
                    "toolNames", stepsSummary.toolNames(),
                    "stepCount", stepsSummary.stepCount(),
                    "totalDurationMs", stepsSummary.totalDurationMs()));
            if (!RoutingAuditExtractor.toPayloadMap(routingSummary).isEmpty()) {
                payload.put("routing", RoutingAuditExtractor.toPayloadMap(routingSummary));
            }
            String payloadJson = objectMapper.writeValueAsString(payload);
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
