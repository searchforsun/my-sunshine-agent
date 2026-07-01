package com.sunshine.rag.repository;

import com.sunshine.rag.entity.DocumentVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocumentVersionRepository extends JpaRepository<DocumentVersionEntity, Long> {
    List<DocumentVersionEntity> findByTenantIdAndKbIdAndDocIdOrderByVersionDesc(
            String tenantId, String kbId, String docId);

    List<DocumentVersionEntity> findByTenantIdAndKbIdAndDocIdAndStatus(
            String tenantId, String kbId, String docId, String status);

    Optional<DocumentVersionEntity> findFirstByTenantIdAndKbIdAndDocIdAndStatusOrderByVersionDesc(
            String tenantId, String kbId, String docId, String status);

    Optional<DocumentVersionEntity> findByTenantIdAndKbIdAndDocIdAndVersion(
            String tenantId, String kbId, String docId, int version);
}
