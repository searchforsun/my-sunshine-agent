package com.sunshine.tool.repo;

import com.sunshine.tool.entity.ExecutionModePolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExecutionModePolicyRepository extends JpaRepository<ExecutionModePolicyEntity, String> {

    Optional<ExecutionModePolicyEntity> findByModeKeyAndTenantId(String modeKey, String tenantId);
}
