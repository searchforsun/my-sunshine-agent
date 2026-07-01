package com.sunshine.rag.repository;

import com.sunshine.rag.entity.BadcaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BadcaseRepository extends JpaRepository<BadcaseEntity, Long> {
    List<BadcaseEntity> findByTenantIdAndKbIdOrderByCreatedAtDesc(String tenantId, String kbId);
}
