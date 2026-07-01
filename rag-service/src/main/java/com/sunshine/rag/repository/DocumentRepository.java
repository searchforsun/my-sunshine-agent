package com.sunshine.rag.repository;

import com.sunshine.rag.entity.DocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<DocumentEntity, Long> {
    List<DocumentEntity> findByTenantIdAndKbIdOrderByDocIdAsc(String tenantId, String kbId);

    Optional<DocumentEntity> findByTenantIdAndKbIdAndDocId(String tenantId, String kbId, String docId);
}
