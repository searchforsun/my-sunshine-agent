package com.sunshine.model.repo;

import com.sunshine.model.entity.ModelDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ModelDefinitionRepository extends JpaRepository<ModelDefinitionEntity, Long> {
    List<ModelDefinitionEntity> findByTenantIdOrderBySortOrderAscModelNameAsc(String tenantId);

    Optional<ModelDefinitionEntity> findByTenantIdAndModelName(String tenantId, String modelName);

    boolean existsByTenantIdAndModelName(String tenantId, String modelName);

    List<ModelDefinitionEntity> findByTenantIdAndProviderKey(String tenantId, String providerKey);
}
