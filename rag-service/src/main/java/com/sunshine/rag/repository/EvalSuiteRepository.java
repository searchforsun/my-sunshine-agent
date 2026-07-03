package com.sunshine.rag.repository;

import com.sunshine.rag.entity.EvalSuiteEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EvalSuiteRepository extends JpaRepository<EvalSuiteEntity, Long> {
    List<EvalSuiteEntity> findByTenantIdOrderByUpdatedAtDesc(String tenantId);
    Optional<EvalSuiteEntity> findByTenantIdAndSuiteKey(String tenantId, String suiteKey);
    boolean existsByTenantIdAndSuiteKey(String tenantId, String suiteKey);
}
