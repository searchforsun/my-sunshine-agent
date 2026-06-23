package com.sunshine.orchestrator.plan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExecutionPlanRepository extends JpaRepository<ExecutionPlanEntity, String> {

    List<ExecutionPlanEntity> findByConversationIdOrderByCreatedAtDesc(String conversationId);

    Optional<ExecutionPlanEntity> findByMessageId(String messageId);
}
