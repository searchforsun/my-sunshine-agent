package com.sunshine.orchestrator.context.l2;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserContextStateRepository extends JpaRepository<UserContextStateEntity, String> {

    List<UserContextStateEntity> findByUserIdAndTenantIdAndStatus(
            String userId, String tenantId, String status);

    Optional<UserContextStateEntity> findByUserIdAndTenantIdAndKindAndStateKey(
            String userId, String tenantId, String kind, String stateKey);
}
