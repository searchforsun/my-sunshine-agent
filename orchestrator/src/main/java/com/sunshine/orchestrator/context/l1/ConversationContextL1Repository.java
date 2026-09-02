package com.sunshine.orchestrator.context.l1;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConversationContextL1Repository extends JpaRepository<ConversationContextL1Entity, String> {

    List<ConversationContextL1Entity> findByUserIdAndTenantId(String userId, String tenantId);
}
