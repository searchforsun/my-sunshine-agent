package com.sunshine.model.repo;

import com.sunshine.model.entity.ModelRoutePolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ModelRoutePolicyRepository extends JpaRepository<ModelRoutePolicyEntity, Long> {
    List<ModelRoutePolicyEntity> findByTenantIdOrderByCallSiteAsc(String tenantId);

    Optional<ModelRoutePolicyEntity> findByTenantIdAndCallSite(String tenantId, String callSite);
}
