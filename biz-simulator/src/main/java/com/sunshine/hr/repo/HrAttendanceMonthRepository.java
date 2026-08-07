package com.sunshine.hr.repo;

import com.sunshine.hr.entity.HrAttendanceMonthEntity;
import com.sunshine.hr.entity.HrAttendanceMonthId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HrAttendanceMonthRepository extends JpaRepository<HrAttendanceMonthEntity, HrAttendanceMonthId> {

    List<HrAttendanceMonthEntity> findByTenantIdOrderByUserIdAscYearMonthAsc(String tenantId);

    List<HrAttendanceMonthEntity> findByTenantIdAndUserIdOrderByYearMonthAsc(String tenantId, String userId);

    Optional<HrAttendanceMonthEntity> findByTenantIdAndUserIdAndYearMonth(
            String tenantId, String userId, String yearMonth);
}
