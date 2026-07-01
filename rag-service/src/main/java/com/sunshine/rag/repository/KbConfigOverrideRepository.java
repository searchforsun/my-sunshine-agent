package com.sunshine.rag.repository;

import com.sunshine.rag.entity.KbConfigOverrideEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface KbConfigOverrideRepository extends JpaRepository<KbConfigOverrideEntity, Long> {
    Optional<KbConfigOverrideEntity> findByTenantIdAndKbId(String tenantId, String kbId);
}
