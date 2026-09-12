package com.sunshine.orchestrator.usage;

import com.sunshine.orchestrator.usage.entity.LlmUsageDailyEntity;
import com.sunshine.orchestrator.usage.repo.LlmUsageDailyRepository;
import com.sunshine.orchestrator.usage.repo.LlmUsageRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** llm_usage_record → llm_usage_daily 日聚合（phase5 5.2.3）：删除重建保证幂等，est_cost 按模型单价估算。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UsageDailyAggregationJob {

    private final LlmUsageRecordRepository recordRepository;
    private final LlmUsageDailyRepository dailyRepository;
    private final LlmUsageProperties properties;

    @Scheduled(fixedDelayString = "${sunshine.llm-usage.aggregate-interval-ms:300000}",
            initialDelay = 60_000)
    @Transactional
    public void aggregate() {
        // 日期窗口即分区键：聚合按 DATE(request_at) 分组，删除窗口必须与分组口径一致，否则窗口外分组会撞历史残留行
        LocalDate today = LocalDate.now();
        LocalDate since = today.minusDays(Math.max(1, properties.getAggregateLookbackDays()));
        LocalDate until = today.plusDays(1);

        dailyRepository.deleteRange(since, until);
        List<LlmUsageRecordRepository.DailyAggView> rows = recordRepository.aggregateDaily(since, until);
        for (LlmUsageRecordRepository.DailyAggView row : rows) {
            LlmUsageDailyEntity entity = new LlmUsageDailyEntity();
            Date statDate = row.getStatDate();
            entity.setStatDate(statDate != null ? statDate.toLocalDate() : today);
            entity.setTenantId(row.getTenantId() != null ? row.getTenantId() : "default");
            entity.setModel(row.getModel());
            entity.setCallSite(row.getCallSite());
            entity.setCalls(row.getCalls() == null ? 0 : row.getCalls().intValue());
            entity.setPromptTokens(row.getPromptTokens() == null ? 0 : row.getPromptTokens());
            entity.setCompletionTokens(row.getCompletionTokens() == null ? 0 : row.getCompletionTokens());
            entity.setTotalTokens(row.getTotalTokens() == null ? 0 : row.getTotalTokens());
            entity.setEstCost(estimateCost(row.getModel(), entity.getPromptTokens(), entity.getCompletionTokens()));
            entity.setUpdatedAt(Instant.now());
            dailyRepository.save(entity);
        }
        if (!rows.isEmpty()) {
            log.info("[LlmUsage] 日聚合完成: 窗口 {}~{} rows={}", since, until, rows.size());
        }
    }

    private BigDecimal estimateCost(String model, long promptTokens, long completionTokens) {
        LlmUsageProperties.ModelPrice price = properties.getPrice().get(model);
        if (price == null) {
            return BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);
        }
        double cost = promptTokens / 1_000_000d * price.getInputPer1m()
                + completionTokens / 1_000_000d * price.getOutputPer1m();
        return BigDecimal.valueOf(cost).setScale(6, RoundingMode.HALF_UP);
    }
}
