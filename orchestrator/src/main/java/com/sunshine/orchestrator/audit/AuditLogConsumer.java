package com.sunshine.orchestrator.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.annotation.RocketMQMessageListener;
import org.apache.rocketmq.client.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "sunshine.audit", name = "enabled", havingValue = "true", matchIfMissing = true)
@RocketMQMessageListener(
        endpoints = "${rocketmq.push-consumer.endpoints}",
        topic = "${sunshine.audit.topic:sunshine-audit}",
        consumerGroup = "${sunshine.audit.consumer-group:sunshine-audit-consumer}",
        tag = "*")
public class AuditLogConsumer implements RocketMQListener {

    private final AuditPersistService auditPersistService;
    // 必须用 Spring 注入的 ObjectMapper（含 JavaTimeModule）：AuditEvent.createdAt 为 Instant，
    // 手动 new ObjectMapper() 无法反序列化 producer 发出的 ISO-8601 时间，会导致消费失败丢审计
    private final ObjectMapper objectMapper;

    @Override
    public ConsumeResult consume(MessageView messageView) {
        try {
            String message = StandardCharsets.UTF_8.decode(messageView.getBody()).toString();
            AuditEvent event = objectMapper.readValue(message, AuditEvent.class);
            auditPersistService.persist(event);
            log.info("[Audit] MQ 落库 messageId={} status={}", event.messageId(), event.status());
            return ConsumeResult.SUCCESS;
        } catch (Exception e) {
            log.warn("[Audit] 消费失败: {}", e.getMessage());
            return ConsumeResult.SUCCESS;
        }
    }
}
