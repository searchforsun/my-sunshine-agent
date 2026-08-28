package com.sunshine.orchestrator.usage;

import com.sunshine.orchestrator.usage.entity.LlmUsageDailyEntity;
import com.sunshine.orchestrator.usage.repo.LlmUsageDailyRepository;
import com.sunshine.orchestrator.usage.repo.LlmUsageRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UsageDailyAggregationJobTest {

    @Mock
    private LlmUsageRecordRepository recordRepository;

    @Mock
    private LlmUsageDailyRepository dailyRepository;

    private UsageDailyAggregationJob job;

    @BeforeEach
    void setUp() {
        LlmUsageProperties properties = new LlmUsageProperties();
        properties.setAggregateLookbackDays(2);
        LlmUsageProperties.ModelPrice price = new LlmUsageProperties.ModelPrice();
        price.setInputPer1m(1.0);
        price.setOutputPer1m(3.0);
        properties.getPrice().put("deepseek-v4-flash", price);
        job = new UsageDailyAggregationJob(recordRepository, dailyRepository, properties);
    }

    private LlmUsageRecordRepository.DailyAggView view(
            String date, String tenant, String model, String callSite, long calls, long prompt, long completion) {
        return new LlmUsageRecordRepository.DailyAggView() {
            @Override
            public Date getStatDate() {
                return Date.valueOf(date);
            }

            @Override
            public String getTenantId() {
                return tenant;
            }

            @Override
            public String getModel() {
                return model;
            }

            @Override
            public String getCallSite() {
                return callSite;
            }

            @Override
            public Long getCalls() {
                return calls;
            }

            @Override
            public Long getPromptTokens() {
                return prompt;
            }

            @Override
            public Long getCompletionTokens() {
                return completion;
            }

            @Override
            public Long getTotalTokens() {
                return prompt + completion;
            }
        };
    }

    @Test
    void aggregate_buildsRowsWithCostEstimate() {
        String today = LocalDate.now().toString();
        when(recordRepository.aggregateDaily(any(), any())).thenReturn(List.of(
                view(today, "default", "deepseek-v4-flash", "chat", 2, 1_000_000, 500_000),
                view(today, "default", "qwen-plus", null, 1, 100, 50)));

        job.aggregate();

        ArgumentCaptor<LlmUsageDailyEntity> captor = ArgumentCaptor.forClass(LlmUsageDailyEntity.class);
        verify(dailyRepository, times(2)).save(captor.capture());
        List<LlmUsageDailyEntity> saved = captor.getAllValues();

        LlmUsageDailyEntity flash = saved.stream().filter(e -> e.getModel().equals("deepseek-v4-flash")).findFirst().orElseThrow();
        // 1.0 * 1M/1M + 3.0 * 0.5M/1M = 1.0 + 1.5 = 2.5 元
        assertThat(flash.getEstCost()).isEqualByComparingTo("2.500000");
        assertThat(flash.getTotalTokens()).isEqualTo(1_500_000L);
        assertThat(flash.getCallSite()).isEqualTo("chat");

        LlmUsageDailyEntity qwen = saved.stream().filter(e -> e.getModel().equals("qwen-plus")).findFirst().orElseThrow();
        // 未配置单价 → 0
        assertThat(qwen.getEstCost()).isEqualByComparingTo("0.000000");
        assertThat(qwen.getCallSite()).isNull();
    }

    @Test
    void aggregate_deletesWindowBeforeInsert() {
        when(recordRepository.aggregateDaily(any(), any())).thenReturn(List.of());

        job.aggregate();

        verify(dailyRepository).deleteRange(any(), any());
        verify(dailyRepository, times(0)).save(any());
    }
}
