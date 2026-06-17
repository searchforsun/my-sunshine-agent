package com.sunshine.orchestrator.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.audit.entity.ChatAuditLogEntity;
import com.sunshine.orchestrator.audit.repo.ChatAuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "sunshine.audit", name = "enabled", havingValue = "true", matchIfMissing = true)
@RocketMQMessageListener(
        topic = "${sunshine.audit.topic:sunshine-audit}",
        consumerGroup = "${sunshine.audit.consumer-group:sunshine-audit-consumer}")
public class AuditLogConsumer implements RocketMQListener<String> {

    private final AuditPersistService auditPersistService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void onMessage(String message) {
        try {
            AuditEvent event = objectMapper.readValue(message, AuditEvent.class);
            auditPersistService.persist(event);
            log.info("[Audit] MQ 落库 messageId={} status={}", event.messageId(), event.status());
        } catch (Exception e) {
            log.warn("[Audit] 消费失败: {}", e.getMessage());
        }
    }
}
