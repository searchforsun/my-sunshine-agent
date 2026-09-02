package com.sunshine.orchestrator.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.core.RocketMQClientTemplate;
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
    private RocketMQClientTemplate rocketMQClientTemplate;

    public void publish(AuditEvent event) {
        if (!properties.isEnabled()) {
            return;
        }
        if (rocketMQClientTemplate != null) {
            try {
                String json = objectMapper.writeValueAsString(event);
                rocketMQClientTemplate.syncSendNormalMessage(properties.getTopic(), json);
                log.info("[Audit] 已发送 topic={} msgId={}", properties.getTopic(), event.messageId());
                return;
            } catch (Throwable e) {
                // ServiceConfigurationError 等是 Error，不能只 catch Exception，否则会冲垮工具调用 / 终态落库
                log.warn("[Audit] MQ 发送失败，降级直写 msgId={}: {}", event.messageId(), e.toString());
            }
        }
        try {
            auditPersistService.persist(event);
            log.info("[Audit] 直写落库 msgId={}", event.messageId());
        } catch (Throwable e) {
            log.warn("[Audit] 直写落库也失败 msgId={}: {}", event.messageId(), e.toString());
        }
    }
}
