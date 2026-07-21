package com.sunshine.hr.repo;

import com.sunshine.hr.entity.HrLeaveRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HrLeaveRequestRepository extends JpaRepository<HrLeaveRequestEntity, String> {

    List<HrLeaveRequestEntity> findByTenantIdOrderByIdAsc(String tenantId);

    List<HrLeaveRequestEntity> findByTenantIdAndStatusOrderByIdAsc(String tenantId, String status);

    List<HrLeaveRequestEntity> findByTenantIdAndUserIdOrderByIdAsc(String tenantId, String userId);

    List<HrLeaveRequestEntity> findByTenantIdAndUserIdAndStatusOrderByIdAsc(
            String tenantId, String userId, String status);

    Optional<HrLeaveRequestEntity> findByTenantIdAndUserIdAndId(String tenantId, String userId, String id);
}
