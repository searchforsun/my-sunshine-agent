package com.sunshine.orchestrator.audit;

import com.sunshine.orchestrator.audit.entity.ChatAuditLogEntity;
import com.sunshine.orchestrator.audit.repo.ChatAuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuditPersistService {

    private final ChatAuditLogRepository auditLogRepository;
    private final AuditElasticsearchWriter elasticsearchWriter;

    @Transactional
    public void persist(AuditEvent event) {
        ChatAuditLogEntity row = new ChatAuditLogEntity();
        row.setId(event.id());
        row.setConversationId(event.conversationId());
        row.setMessageId(event.messageId());
        row.setUserId(event.userId());
        row.setTenantId(event.tenantId());
        row.setEventType(event.eventType());
        row.setStatus(event.status());
        row.setIntent(event.intent());
        row.setContentLen(event.contentLen());
        row.setPayload(event.payloadJson());
        row.setCreatedAt(event.createdAt());
        auditLogRepository.save(row);
        elasticsearchWriter.index(event);
    }
}
