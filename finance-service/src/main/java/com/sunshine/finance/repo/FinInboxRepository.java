package com.sunshine.finance.repo;

import com.sunshine.finance.entity.FinInboxEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FinInboxRepository extends JpaRepository<FinInboxEntity, String> {

    List<FinInboxEntity> findByTenantIdAndUserIdOrderByIdAsc(String tenantId, String userId);

    List<FinInboxEntity> findByTenantIdAndUserIdAndStatusOrderByIdAsc(
            String tenantId, String userId, String status);

    Optional<FinInboxEntity> findByTenantIdAndUserIdAndId(String tenantId, String userId, String id);
}
