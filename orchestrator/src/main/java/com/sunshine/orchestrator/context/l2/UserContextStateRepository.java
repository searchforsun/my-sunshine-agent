package com.sunshine.orchestrator.context.l2;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UserContextStateRepository extends JpaRepository<UserContextStateEntity, String> {

    List<UserContextStateEntity> findByUserIdAndTenantIdOrderByUpdatedAtDesc(
            String userId, String tenantId);

    List<UserContextStateEntity> findByUserIdAndTenantIdAndStatus(
            String userId, String tenantId, String status);

    Optional<UserContextStateEntity> findByUserIdAndTenantIdAndKindAndStateKeyAndStatus(
            String userId, String tenantId, String kind, String stateKey, String status);

    /** active 且 expires_at 已过（硬过期 → void） */
    List<UserContextStateEntity> findByStatusAndExpiresAtBefore(String status, Instant expiresAt);

    List<UserContextStateEntity> findByStatus(String status);

    /** 长期 superseded 物理清理 */
    List<UserContextStateEntity> findByStatusAndUpdatedAtBefore(String status, Instant updatedAt);
}
