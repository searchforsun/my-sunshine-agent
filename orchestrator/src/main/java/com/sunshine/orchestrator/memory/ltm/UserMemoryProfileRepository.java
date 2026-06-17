package com.sunshine.orchestrator.memory.ltm;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserMemoryProfileRepository extends JpaRepository<UserMemoryProfileEntity, String> {

    Optional<UserMemoryProfileEntity> findByUserIdAndTenantId(String userId, String tenantId);
}
