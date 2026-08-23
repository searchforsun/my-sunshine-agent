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

    /** 同 key 最新一条 void 行（乱序保护用；同 key 多次完成可存在多条 void）。 */
    Optional<UserContextStateEntity> findFirstByUserIdAndTenantIdAndKindAndStateKeyAndStatusOrderByUpdatedAtDesc(
            String userId, String tenantId, String kind, String stateKey, String status);

    List<UserContextStateEntity> findByWorkspaceIdAndTenantIdAndStatus(
            String workspaceId, String tenantId, String status);

    Optional<UserContextStateEntity> findByWorkspaceIdAndTenantIdAndKindAndStateKeyAndStatus(
            String workspaceId, String tenantId, String kind, String stateKey, String status);

    /** 同 key 最新一条 void 行（workspace 维度）。 */
    Optional<UserContextStateEntity> findFirstByWorkspaceIdAndTenantIdAndKindAndStateKeyAndStatusOrderByUpdatedAtDesc(
            String workspaceId, String tenantId, String kind, String stateKey, String status);

    /** 结构导出全量对比：该 scope 下 kind + state_key 前缀（如 task.）的 active 行。 */
    List<UserContextStateEntity> findByUserIdAndTenantIdAndKindAndStateKeyStartingWithAndStatus(
            String userId, String tenantId, String kind, String stateKeyPrefix, String status);

    List<UserContextStateEntity> findByWorkspaceIdAndTenantIdAndKindAndStateKeyStartingWithAndStatus(
            String workspaceId, String tenantId, String kind, String stateKeyPrefix, String status);

    /** active 且 expires_at 已过（硬过期 → void） */
    List<UserContextStateEntity> findByStatusAndExpiresAtBefore(String status, Instant expiresAt);

    List<UserContextStateEntity> findByStatus(String status);

    /** 长期 superseded 物理清理 */
    List<UserContextStateEntity> findByStatusAndUpdatedAtBefore(String status, Instant updatedAt);
}
