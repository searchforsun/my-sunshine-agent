package com.sunshine.model.repo;

import com.sunshine.model.entity.ModelSceneBindingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ModelSceneBindingRepository extends JpaRepository<ModelSceneBindingEntity, Long> {
    List<ModelSceneBindingEntity> findByTenantIdOrderBySceneKeyAsc(String tenantId);

    Optional<ModelSceneBindingEntity> findByTenantIdAndSceneKey(String tenantId, String sceneKey);
}
