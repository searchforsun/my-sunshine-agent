package com.sunshine.orchestrator.peer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.audit.AuditEvent;
import com.sunshine.orchestrator.audit.AuditPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PeerRunAuditService {

    private final PeerRunRepository repository;
    private final AuditPublisher auditPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void persistFinal(
            String conversationId,
            String messageId,
            String userId,
            String tenantId,
            PeerRoundEngine.PeerRunResult result) {
        if (messageId == null || messageId.isBlank() || result == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(result.transcript());
            PeerRunEntity entity = repository.findByMessageId(messageId).orElseGet(() -> {
                PeerRunEntity created = new PeerRunEntity();
                created.setId(UUID.randomUUID().toString());
                created.setMessageId(messageId);
                created.setCreatedAt(Instant.now());
                return created;
            });
            entity.setConversationId(conversationId);
            entity.setUserId(userId);
            entity.setTenantId(tenantId != null ? tenantId : "default");
            entity.setTemplateId(result.template().id());
            entity.setTranscriptJson(json);
            entity.setUpdatedAt(Instant.now());
            repository.save(entity);
            publishEvent(conversationId, messageId, userId, tenantId, result);
        } catch (Exception e) {
            log.warn("[PeerRunAudit] 落库失败 msg={}: {}", messageId, e.getMessage());
        }
    }

    public Optional<PeerRunAuditView> findByMessageId(String messageId) {
        return repository.findByMessageId(messageId).map(this::toView);
    }

    private PeerRunAuditView toView(PeerRunEntity entity) {
        return new PeerRunAuditView(
                entity.getMessageId(),
                entity.getTemplateId(),
                entity.getTranscriptJson(),
                entity.getUpdatedAt());
    }

    private void publishEvent(
            String conversationId,
            String messageId,
            String userId,
            String tenantId,
            PeerRoundEngine.PeerRunResult result) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("peerRunId", result.runId());
            payload.put("templateId", result.template().id());
            payload.put("entryCount", result.transcript().size());
            String payloadJson = objectMapper.writeValueAsString(payload);
            auditPublisher.publish(new AuditEvent(
                    UUID.randomUUID().toString().replace("-", ""),
                    conversationId != null ? conversationId : "",
                    messageId,
                    userId != null ? userId : "",
                    StringUtils.hasText(tenantId) ? tenantId : "default",
                    "peer_run.final",
                    "ok",
                    null,
                    payloadJson.length(),
                    payloadJson,
                    Instant.now()));
        } catch (Exception e) {
            log.warn("[PeerRunAudit] 事件发布失败 msg={}: {}", messageId, e.getMessage());
        }
    }
}
