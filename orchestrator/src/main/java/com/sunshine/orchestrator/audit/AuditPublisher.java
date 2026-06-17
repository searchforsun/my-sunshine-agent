package com.sunshine.orchestrator.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditPublisher {

    private final AuditProperties properties;
    private final AuditPersistService auditPersistService;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private RocketMQTemplate rocketMQTemplate;

    public void publish(AuditEvent event) {
        if (!properties.isEnabled()) {
            return;
        }
        if (rocketMQTemplate != null) {
            try {
                String json = objectMapper.writeValueAsString(event);
                rocketMQTemplate.convertAndSend(properties.getTopic(), json);
                log.info("[Audit] 已发送 topic={} msgId={}", properties.getTopic(), event.messageId());
                return;
            } catch (Exception e) {
                log.warn("[Audit] MQ 发送失败，降级直写 msgId={}: {}", event.messageId(), e.getMessage());
            }
        }
        auditPersistService.persist(event);
        log.info("[Audit] 直写落库 msgId={}", event.messageId());
    }
}
