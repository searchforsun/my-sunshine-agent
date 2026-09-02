package com.sunshine.orchestrator.usage;

import com.sunshine.orchestrator.usage.entity.LlmUsageRecordEntity;
import com.sunshine.orchestrator.usage.repo.LlmUsageRecordRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class LlmUsagePersistServiceTest {

    @Mock
    private LlmUsageRecordRepository repository;

    @InjectMocks
    private LlmUsagePersistService service;

    @Test
    void persist_mapsMessageToEntity() {
        service.persist(new LlmUsageMessage(
                "default", "u1", "deepseek-v4-pro", "chat", "run-1", "round-2",
                true, 100, 30, 130, false, 1_750_000_000_000L));

        ArgumentCaptor<LlmUsageRecordEntity> captor = ArgumentCaptor.forClass(LlmUsageRecordEntity.class);
        verify(repository).save(captor.capture());
        LlmUsageRecordEntity entity = captor.getValue();
        assertThat(entity.getTenantId()).isEqualTo("default");
        assertThat(entity.getUserId()).isEqualTo("u1");
        assertThat(entity.getModel()).isEqualTo("deepseek-v4-pro");
        assertThat(entity.getCallSite()).isEqualTo("chat");
        assertThat(entity.getRunId()).isEqualTo("run-1");
        assertThat(entity.getRoundId()).isEqualTo("round-2");
        assertThat(entity.isStream()).isTrue();
        assertThat(entity.getPromptTokens()).isEqualTo(100);
        assertThat(entity.getCompletionTokens()).isEqualTo(30);
        assertThat(entity.getTotalTokens()).isEqualTo(130);
        assertThat(entity.isEstimated()).isFalse();
        assertThat(entity.getRequestAt().toEpochMilli()).isEqualTo(1_750_000_000_000L);
        assertThat(entity.getCreatedAt()).isNotNull();
    }

    @Test
    void persist_nullTenant_fallsBackToDefault() {
        service.persist(new LlmUsageMessage(
                null, null, "qwen-plus", null, null, null,
                false, 10, 5, 15, true, System.currentTimeMillis()));

        ArgumentCaptor<LlmUsageRecordEntity> captor = ArgumentCaptor.forClass(LlmUsageRecordEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo("default");
    }

    @Test
    void persist_blankModel_dropped() {
        service.persist(new LlmUsageMessage(
                "default", null, "  ", null, null, null,
                false, 0, 0, 0, false, System.currentTimeMillis()));

        verifyNoInteractions(repository);
    }
}
