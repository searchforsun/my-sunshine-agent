package com.sunshine.orchestrator.usage;

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

/** 用量 MQ 消费（topic=llm-usage）→ 落库 llm_usage_record。模式对齐 AuditLogConsumer。 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "sunshine.llm-usage", name = "enabled", havingValue = "true", matchIfMissing = true)
@RocketMQMessageListener(
        endpoints = "${rocketmq.push-consumer.endpoints}",
        topic = "${sunshine.llm-usage.topic:llm-usage}",
        consumerGroup = "${sunshine.llm-usage.consumer-group:sunshine-llm-usage-consumer}",
        tag = "*")
public class LlmUsageConsumer implements RocketMQListener {

    private final LlmUsagePersistService persistService;
    private final ObjectMapper objectMapper;

    @Override
    public ConsumeResult consume(MessageView messageView) {
        try {
            String message = StandardCharsets.UTF_8.decode(messageView.getBody()).toString();
            LlmUsageMessage usage = objectMapper.readValue(message, LlmUsageMessage.class);
            persistService.persist(usage);
            return ConsumeResult.SUCCESS;
        } catch (Exception e) {
            log.warn("[LlmUsage] 消费失败: {}", e.getMessage());
            return ConsumeResult.SUCCESS;
        }
    }
}
