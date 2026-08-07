package com.sunshine.oa.repo;

import com.sunshine.oa.entity.OaTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OaTaskRepository extends JpaRepository<OaTaskEntity, String> {

    List<OaTaskEntity> findByTenantIdOrderByIdAsc(String tenantId);

    List<OaTaskEntity> findByTenantIdAndStatusOrderByIdAsc(String tenantId, String status);

    List<OaTaskEntity> findByTenantIdAndAssigneeUserIdOrderByIdAsc(String tenantId, String assigneeUserId);

    List<OaTaskEntity> findByTenantIdAndAssigneeUserIdAndStatusOrderByIdAsc(
            String tenantId, String assigneeUserId, String status);

    Optional<OaTaskEntity> findByTenantIdAndAssigneeUserIdAndId(
            String tenantId, String assigneeUserId, String id);
}
