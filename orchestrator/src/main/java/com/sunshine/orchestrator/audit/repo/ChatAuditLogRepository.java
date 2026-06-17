package com.sunshine.orchestrator.audit.repo;

import com.sunshine.orchestrator.audit.entity.ChatAuditLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatAuditLogRepository extends JpaRepository<ChatAuditLogEntity, String> {

    List<ChatAuditLogEntity> findTop20ByOrderByCreatedAtDesc();
}
