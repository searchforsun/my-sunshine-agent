package com.sunshine.orchestrator.usage;

import com.sunshine.orchestrator.usage.entity.LlmUsageRecordEntity;
import com.sunshine.orchestrator.usage.repo.LlmUsageRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

/** 用量记录落库：MQ 消息 → llm_usage_record 行（phase5 5.2.2）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmUsagePersistService {

    private final LlmUsageRecordRepository repository;

    public void persist(LlmUsageMessage message) {
        if (message == null || message.model() == null || message.model().isBlank()) {
            log.warn("[LlmUsage] 消息缺 model，丢弃");
            return;
        }
        LlmUsageRecordEntity entity = new LlmUsageRecordEntity();
        entity.setTenantId(message.tenantId() != null ? message.tenantId() : "default");
        entity.setUserId(message.userId());
        entity.setModel(message.model());
        entity.setCallSite(message.callSite());
        entity.setRunId(message.runId());
        entity.setRoundId(message.roundId());
        entity.setStream(message.stream());
        entity.setPromptTokens(message.promptTokens());
        entity.setCompletionTokens(message.completionTokens());
        entity.setTotalTokens(message.totalTokens());
        entity.setEstimated(message.estimated());
        entity.setRequestAt(Instant.ofEpochMilli(message.requestAtEpochMillis()));
        entity.setCreatedAt(Instant.now());
        repository.save(entity);
        log.info("[LlmUsage] 落库 model={} total={} estimated={}",
                entity.getModel(), entity.getTotalTokens(), entity.isEstimated());
    }
}
