package com.sunshine.rag.repository;

import com.sunshine.rag.entity.IngestJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IngestJobRepository extends JpaRepository<IngestJobEntity, Long> {
    List<IngestJobEntity> findByTenantIdAndKbIdOrderByCreatedAtDesc(String tenantId, String kbId);

    java.util.Optional<IngestJobEntity> findByIdAndTenantIdAndKbId(Long id, String tenantId, String kbId);
}
