package com.sunshine.rag.repository;

import com.sunshine.rag.entity.KnowledgeBaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBaseEntity, Long> {
    List<KnowledgeBaseEntity> findByTenantIdOrderByKbIdAsc(String tenantId);

    Optional<KnowledgeBaseEntity> findByTenantIdAndKbId(String tenantId, String kbId);

    Optional<KnowledgeBaseEntity> findByTenantIdAndIsDefaultTrue(String tenantId);
}
