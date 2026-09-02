package com.sunshine.orchestrator.usage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.usage.entity.TenantQuotaEntity;
import com.sunshine.orchestrator.usage.repo.LlmUsageRecordRepository;
import com.sunshine.orchestrator.usage.repo.TenantQuotaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantQuotaServiceTest {

    @Mock
    private TenantQuotaRepository repository;

    @Mock
    private LlmUsageRecordRepository usageRepository;

    private TenantQuotaService service;

    @BeforeEach
    void setUp() {
        service = new TenantQuotaService(repository, usageRepository, new ObjectMapper());
    }

    private TenantQuotaEntity quota(String tenantId, long limit, String whitelist, boolean enabled) {
        TenantQuotaEntity e = new TenantQuotaEntity();
        e.setTenantId(tenantId);
        e.setMonthTokenLimit(limit);
        e.setModelWhitelist(whitelist);
        e.setEnabled(enabled);
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        return e;
    }

    @Test
    void check_noQuota_allowed() {
        when(repository.findByTenantId("t1")).thenReturn(Optional.empty());

        Map<String, Object> result = service.check("t1", "deepseek-v4-flash");

        assertThat(result.get("allowed")).isEqualTo(true);
        assertThat(result.get("code")).isEqualTo(TenantQuotaService.CODE_ALLOWED);
    }

    @Test
    void check_quotaDisabled_allowed() {
        when(repository.findByTenantId("t1")).thenReturn(Optional.of(quota("t1", 1000, null, false)));

        Map<String, Object> result = service.check("t1", "deepseek-v4-flash");

        assertThat(result.get("allowed")).isEqualTo(true);
    }

    @Test
    void check_modelNotInWhitelist_rejected() {
        when(repository.findByTenantId("t1")).thenReturn(Optional.of(
                quota("t1", 1000, "[\"qwen-plus\"]", true)));

        Map<String, Object> result = service.check("t1", "deepseek-v4-flash");

        assertThat(result.get("allowed")).isEqualTo(false);
        assertThat(result.get("code")).isEqualTo(TenantQuotaService.CODE_MODEL_NOT_ALLOWED);
    }

    @Test
    void check_monthlyLimitExceeded_rejected() {
        when(repository.findByTenantId("t1")).thenReturn(Optional.of(quota("t1", 1000, null, true)));
        when(usageRepository.sumTokensBetween(any(), any(), anyString())).thenReturn(1500L);

        Map<String, Object> result = service.check("t1", "deepseek-v4-flash");

        assertThat(result.get("allowed")).isEqualTo(false);
        assertThat(result.get("code")).isEqualTo(TenantQuotaService.CODE_QUOTA_EXCEEDED);
        assertThat(result.get("monthlyUsed")).isEqualTo(1500L);
        assertThat(result.get("monthlyLimit")).isEqualTo(1000L);
    }

    @Test
    void check_withinLimit_allowed() {
        when(repository.findByTenantId("t1")).thenReturn(Optional.of(quota("t1", 10_000, null, true)));
        when(usageRepository.sumTokensBetween(any(), any(), anyString())).thenReturn(500L);

        Map<String, Object> result = service.check("t1", "deepseek-v4-flash");

        assertThat(result.get("allowed")).isEqualTo(true);
        assertThat(result.get("monthlyLimit")).isEqualTo(10_000L);
    }

    @Test
    void check_blankTenant_usesDefault() {
        when(repository.findByTenantId("default")).thenReturn(Optional.empty());

        Map<String, Object> result = service.check(null, "deepseek-v4-flash");

        assertThat(result.get("allowed")).isEqualTo(true);
        verify(repository).findByTenantId("default");
    }

    @Test
    void upsert_new_inserts() {
        when(repository.findByTenantId("t1")).thenReturn(Optional.empty());
        TenantQuotaEntity saved = quota("t1", 5000, null, true);
        saved.setId(1L);
        when(repository.save(any(TenantQuotaEntity.class))).thenReturn(saved);

        TenantQuotaEntity entity = quota("t1", 5000, null, true);
        TenantQuotaEntity result = service.upsert(entity);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getCreatedAt()).isNotNull();
        assertThat(result.getUpdatedAt()).isNotNull();
    }

    @Test
    void upsert_existing_updates() {
        TenantQuotaEntity existing = quota("t1", 100, null, true);
        existing.setId(7L);
        when(repository.findByTenantId("t1")).thenReturn(Optional.of(existing));

        TenantQuotaEntity draft = quota("t1", 999, null, false);
        service.upsert(draft);

        assertThat(existing.getMonthTokenLimit()).isEqualTo(999);
        assertThat(existing.isEnabled()).isFalse();
        assertThat(existing.getModelWhitelist()).isNull();
        verify(repository).save(existing);
    }
}
