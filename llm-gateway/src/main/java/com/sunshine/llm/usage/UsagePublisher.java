package com.sunshine.llm.usage;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.core.RocketMQClientTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 用量记录 MQ 生产（topic=llm-usage）。MQ 不可用时降级为日志（用量统计允许丢点，不阻塞 LLM 调用）。
 * 模式对齐 orchestrator AuditPublisher：@Autowired(required=false) 注入避免无 rocketmq 配置时装配失败。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UsagePublisher {

    private final LlmUsageProperties properties;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private RocketMQClientTemplate rocketMQClientTemplate;

    public void publish(LlmUsageRecord record) {
        if (!properties.isEnabled() || record == null) {
            return;
        }
        if (rocketMQClientTemplate != null) {
            try {
                String json = objectMapper.writeValueAsString(record);
                rocketMQClientTemplate.syncSendNormalMessage(properties.getTopic(), json);
                log.info("[LlmUsage] sent model={} callSite={} stream={}",
                        record.model(), record.callSite(), record.stream());
                return;
            } catch (Throwable e) {
                log.warn("[LlmUsage] MQ 发送失败，降级日志: {}", e.toString());
            }
        }
        log.info("[LlmUsage] record model={} prompt={} completion={} total={} estimated={}",
                record.model(), record.promptTokens(), record.completionTokens(),
                record.totalTokens(), record.estimated());
    }
}
