package com.sunshine.bizscene.repo;

import com.sunshine.bizscene.entity.BizScenePolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BizScenePolicyRepository extends JpaRepository<BizScenePolicyEntity, Long> {
    List<BizScenePolicyEntity> findByTenantIdOrderByBizSceneAscVersionAsc(String tenantId);

    List<BizScenePolicyEntity> findByBizSceneOrderByVersionDesc(String bizScene);

    Optional<BizScenePolicyEntity> findTopByTenantIdAndBizSceneAndStatusOrderByVersionDesc(
            String tenantId, String bizScene, String status);

    List<BizScenePolicyEntity> findByStatusOrderByBizSceneAscVersionAsc(String status);
}
