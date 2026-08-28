package com.sunshine.orchestrator.usage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.usage.entity.TenantQuotaEntity;
import com.sunshine.orchestrator.usage.repo.LlmUsageRecordRepository;
import com.sunshine.orchestrator.usage.repo.TenantQuotaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 租户配额（phase5 5.2.4）：CRUD + 校验单点（白名单 + 月 token 上限）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantQuotaService {

    public static final String CODE_ALLOWED = "ok";
    public static final String CODE_QUOTA_EXCEEDED = "quota_exceeded";
    public static final String CODE_MODEL_NOT_ALLOWED = "model_not_allowed";

    private final TenantQuotaRepository repository;
    private final LlmUsageRecordRepository usageRepository;
    private final ObjectMapper objectMapper;

    public List<TenantQuotaEntity> list() {
        return repository.findAllByOrderByTenantIdAsc();
    }

    @Transactional
    public TenantQuotaEntity upsert(TenantQuotaEntity entity) {
        TenantQuotaEntity existing = repository.findByTenantId(entity.getTenantId()).orElse(null);
        Instant now = Instant.now();
        if (existing == null) {
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            return repository.save(entity);
        }
        existing.setMonthTokenLimit(entity.getMonthTokenLimit());
        existing.setModelWhitelist(entity.getModelWhitelist());
        existing.setEnabled(entity.isEnabled());
        existing.setRemark(entity.getRemark());
        existing.setUpdatedAt(now);
        return repository.save(existing);
    }

    @Transactional
    public void delete(String tenantId) {
        repository.findByTenantId(tenantId).ifPresent(repository::delete);
    }

    /**
     * 请求前配额校验：白名单拒绝或月度用量达上限返回 allowed=false + 明确错误码。
     * 未配置配额 / 未启用 → 放行。月度用量统计口径与 llm_usage_record 落库最终一致。
     */
    public Map<String, Object> check(String tenantId, String model) {
        String actualTenant = tenantId != null && !tenantId.isBlank() ? tenantId.strip() : "default";
        TenantQuotaEntity quota = repository.findByTenantId(actualTenant).orElse(null);
        if (quota == null || !quota.isEnabled()) {
            return checkResult(true, CODE_ALLOWED, 0, 0);
        }
        if (!modelAllowed(quota, model)) {
            return checkResult(false, CODE_MODEL_NOT_ALLOWED, 0, quota.getMonthTokenLimit());
        }
        if (quota.getMonthTokenLimit() > 0) {
            YearMonth month = YearMonth.now(ZoneId.systemDefault());
            Instant from = month.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
            Instant to = month.plusMonths(1).atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
            long monthlyUsed = usageRepository.sumTokensBetween(from, to, actualTenant);
            if (monthlyUsed >= quota.getMonthTokenLimit()) {
                log.info("[LlmQuota] 租户 {} 月度配额超限 used={} limit={}",
                        actualTenant, monthlyUsed, quota.getMonthTokenLimit());
                return checkResult(false, CODE_QUOTA_EXCEEDED, monthlyUsed, quota.getMonthTokenLimit());
            }
        }
        return checkResult(true, CODE_ALLOWED, 0, quota.getMonthTokenLimit());
    }

    private boolean modelAllowed(TenantQuotaEntity quota, String model) {
        if (quota.getModelWhitelist() == null || quota.getModelWhitelist().isBlank()) {
            return true;
        }
        try {
            Set<String> whitelist = objectMapper.readValue(
                    quota.getModelWhitelist(), new TypeReference<Set<String>>() {});
            return whitelist.contains(model);
        } catch (Exception e) {
            log.warn("[LlmQuota] 白名单解析失败 tenant={} raw={}", quota.getTenantId(), quota.getModelWhitelist(), e);
            return true;
        }
    }

    private Map<String, Object> checkResult(boolean allowed, String code, long monthlyUsed, long monthlyLimit) {
        return Map.of(
                "allowed", allowed,
                "code", code,
                "monthlyUsed", monthlyUsed,
                "monthlyLimit", monthlyLimit);
    }
}
