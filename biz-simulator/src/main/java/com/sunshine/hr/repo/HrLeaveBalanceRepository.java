package com.sunshine.hr.repo;

import com.sunshine.hr.entity.HrLeaveBalanceEntity;
import com.sunshine.hr.entity.HrLeaveBalanceId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HrLeaveBalanceRepository extends JpaRepository<HrLeaveBalanceEntity, HrLeaveBalanceId> {

    List<HrLeaveBalanceEntity> findByTenantIdOrderByUserIdAscYearAsc(String tenantId);

    List<HrLeaveBalanceEntity> findByTenantIdAndUserIdOrderByYearAsc(String tenantId, String userId);

    Optional<HrLeaveBalanceEntity> findByTenantIdAndUserIdAndYear(String tenantId, String userId, Integer year);
}
