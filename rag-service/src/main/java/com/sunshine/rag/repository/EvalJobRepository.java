package com.sunshine.rag.repository;

import com.sunshine.rag.entity.EvalJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EvalJobRepository extends JpaRepository<EvalJobEntity, Long> {

    List<EvalJobEntity> findByTenantIdAndKbIdOrderByCreatedAtDesc(String tenantId, String kbId);

    List<EvalJobEntity> findByTenantIdAndKbIdAndStatusIn(String tenantId, String kbId, List<String> statuses);

    List<EvalJobEntity> findByStatusInOrderByCreatedAtAsc(List<String> statuses);
}
