package com.sunshine.orchestrator.taskboard;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReactTaskBoardRepository extends JpaRepository<ReactTaskBoardEntity, String> {

    Optional<ReactTaskBoardEntity> findByMessageId(String messageId);
}
