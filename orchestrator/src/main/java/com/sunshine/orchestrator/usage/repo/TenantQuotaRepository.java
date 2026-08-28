package com.sunshine.orchestrator.usage.repo;

import com.sunshine.orchestrator.usage.entity.TenantQuotaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TenantQuotaRepository extends JpaRepository<TenantQuotaEntity, Long> {

    Optional<TenantQuotaEntity> findByTenantId(String tenantId);

    List<TenantQuotaEntity> findAllByOrderByTenantIdAsc();
}
