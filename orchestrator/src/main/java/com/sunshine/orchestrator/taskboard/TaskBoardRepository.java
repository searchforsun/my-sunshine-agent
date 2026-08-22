package com.sunshine.orchestrator.taskboard;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TaskBoardRepository extends JpaRepository<TaskBoardEntity, String> {

    Optional<TaskBoardEntity> findByMessageId(String messageId);

    Optional<TaskBoardEntity> findFirstByConversationIdOrderByUpdatedAtDesc(String conversationId);
}
