package com.sunshine.rag.repository;

import com.sunshine.rag.entity.ConfigDraftEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConfigDraftRepository extends JpaRepository<ConfigDraftEntity, Long> {
    List<ConfigDraftEntity> findByTenantIdAndStatusOrderByCreatedAtDesc(String tenantId, String status);

    Optional<ConfigDraftEntity> findFirstByTenantIdAndScopeAndStatusOrderByCreatedAtDesc(
            String tenantId, String scope, String status);
}
