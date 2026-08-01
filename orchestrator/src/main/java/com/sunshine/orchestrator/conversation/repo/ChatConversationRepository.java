package com.sunshine.orchestrator.conversation.repo;

import com.sunshine.orchestrator.conversation.entity.ChatConversationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ChatConversationRepository extends JpaRepository<ChatConversationEntity, String> {

    List<ChatConversationEntity> findByUserIdAndTenantIdOrderByUpdatedAtDesc(
            String userId, String tenantId);

    @Transactional
    void deleteByWorkspaceId(String workspaceId);
}
