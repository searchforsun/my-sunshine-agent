package com.sunshine.finance.repo;

import com.sunshine.finance.entity.FinExpenseEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FinExpenseRepository extends JpaRepository<FinExpenseEntity, String> {

    List<FinExpenseEntity> findByTenantIdAndUserIdOrderByIdAsc(String tenantId, String userId);

    List<FinExpenseEntity> findByTenantIdAndUserIdAndStatusOrderByIdAsc(
            String tenantId, String userId, String status);

    Optional<FinExpenseEntity> findByTenantIdAndUserIdAndId(String tenantId, String userId, String id);
}
