package com.sunshine.orchestrator.usage;

/**
 * llm-gateway 用量 MQ 消息（topic=llm-usage）DTO——与 llm-gateway {@code LlmUsageRecord} 同字段结构，
 * Jackson 按组件名匹配反序列化；维度字段为链路透传预留（阶段一可能为 null）。
 */
public record LlmUsageMessage(
        String tenantId,
        String userId,
        String model,
        String callSite,
        String runId,
        String roundId,
        boolean stream,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        boolean estimated,
        long requestAtEpochMillis) {
}
