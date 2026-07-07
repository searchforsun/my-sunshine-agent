package com.sunshine.orchestrator.peer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PeerRunRepository extends JpaRepository<PeerRunEntity, String> {

    Optional<PeerRunEntity> findByMessageId(String messageId);
}
