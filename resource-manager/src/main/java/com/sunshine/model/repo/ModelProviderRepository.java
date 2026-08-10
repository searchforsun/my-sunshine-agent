package com.sunshine.model.repo;

import com.sunshine.model.entity.ModelProviderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ModelProviderRepository extends JpaRepository<ModelProviderEntity, Long> {
    List<ModelProviderEntity> findByTenantIdOrderByProviderKeyAsc(String tenantId);

    Optional<ModelProviderEntity> findByTenantIdAndProviderKey(String tenantId, String providerKey);

    boolean existsByTenantIdAndProviderKey(String tenantId, String providerKey);
}
