package com.sunshine.orchestrator.memory.mtm;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConversationMemoryRepository extends JpaRepository<ConversationMemoryEntity, String> {

    Optional<ConversationMemoryEntity> findByConvId(String convId);

    List<ConversationMemoryEntity> findTop5ByUserIdAndTenantIdOrderByUpdatedAtDesc(
            String userId, String tenantId);
}
