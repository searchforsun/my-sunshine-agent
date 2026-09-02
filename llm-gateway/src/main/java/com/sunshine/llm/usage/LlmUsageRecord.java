package com.sunshine.llm.usage;

/**
 * LLM 调用用量记录（phase5 5.2）——MQ 传输载体，消费端 orchestrator 落库 llm_usage_record。
 * 维度字段（userId/callSite/runId/roundId）为链路透传预留：阶段一取不到时置 null，5.3 配套后填充。
 */
public record LlmUsageRecord(
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
