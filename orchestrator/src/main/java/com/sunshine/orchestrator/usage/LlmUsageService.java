package com.sunshine.orchestrator.usage;

import com.sunshine.orchestrator.usage.entity.LlmUsageDailyEntity;
import com.sunshine.orchestrator.usage.entity.LlmUsageRecordEntity;
import com.sunshine.orchestrator.usage.repo.LlmUsageDailyRepository;
import com.sunshine.orchestrator.usage.repo.LlmUsageRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 用量查询（phase5 5.2）：records=明细 / summary=按 model 聚合（含成本估算）/ daily=日聚合趋势。 */
@Service
@RequiredArgsConstructor
public class LlmUsageService {

    private final LlmUsageRecordRepository repository;
    private final LlmUsageDailyRepository dailyRepository;
    private final LlmUsageProperties properties;

    public List<Map<String, Object>> search(Long sinceEpoch, Long untilEpoch, String model, String tenantId) {
        Instant since = sinceEpoch != null ? Instant.ofEpochMilli(sinceEpoch) : null;
        Instant until = untilEpoch != null ? Instant.ofEpochMilli(untilEpoch) : null;
        return repository.search(since, until, blankToNull(model), blankToNull(tenantId))
                .stream().map(this::toMap).toList();
    }

    public List<Map<String, Object>> summary(Long sinceEpoch, Long untilEpoch, String tenantId) {
        Instant since = sinceEpoch != null ? Instant.ofEpochMilli(sinceEpoch) : null;
        Instant until = untilEpoch != null ? Instant.ofEpochMilli(untilEpoch) : null;
        return repository.aggregateByModel(since, until, blankToNull(tenantId))
                .stream().map(view -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("model", view.getModel());
                    row.put("calls", view.getCalls());
                    row.put("totalTokens", view.getTotalTokens());
                    row.put("promptTokens", view.getPromptTokens());
                    row.put("completionTokens", view.getCompletionTokens());
                    row.put("estCost", estimateCost(view.getModel(), view.getPromptTokens(), view.getCompletionTokens()));
                    return row;
                }).toList();
    }

    public List<Map<String, Object>> daily(Long sinceEpoch, Long untilEpoch, String tenantId, String model) {
        LocalDate since = sinceEpoch != null ? Instant.ofEpochMilli(sinceEpoch).atZone(java.time.ZoneId.systemDefault()).toLocalDate() : null;
        LocalDate until = untilEpoch != null ? Instant.ofEpochMilli(untilEpoch).atZone(java.time.ZoneId.systemDefault()).toLocalDate() : null;
        return dailyRepository.searchDaily(since, until, blankToNull(tenantId), blankToNull(model))
                .stream().map(this::toDailyMap).toList();
    }

    private Map<String, Object> toDailyMap(LlmUsageDailyEntity e) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("statDate", e.getStatDate().toString());
        row.put("tenantId", e.getTenantId());
        row.put("model", e.getModel());
        row.put("callSite", e.getCallSite());
        row.put("calls", e.getCalls());
        row.put("promptTokens", e.getPromptTokens());
        row.put("completionTokens", e.getCompletionTokens());
        row.put("totalTokens", e.getTotalTokens());
        row.put("estCost", e.getEstCost());
        return row;
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

    private Map<String, Object> toMap(LlmUsageRecordEntity e) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", e.getId());
        row.put("tenantId", e.getTenantId());
        row.put("userId", e.getUserId());
        row.put("model", e.getModel());
        row.put("callSite", e.getCallSite());
        row.put("runId", e.getRunId());
        row.put("roundId", e.getRoundId());
        row.put("stream", e.isStream());
        row.put("promptTokens", e.getPromptTokens());
        row.put("completionTokens", e.getCompletionTokens());
        row.put("totalTokens", e.getTotalTokens());
        row.put("estimated", e.isEstimated());
        row.put("requestAt", e.getRequestAt().toEpochMilli());
        return row;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
