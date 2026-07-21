package com.sunshine.auth.repo;

import com.sunshine.auth.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<UserEntity, String> {

    Optional<UserEntity> findByUsername(String username);

    List<UserEntity> findByTenantIdAndStatus(String tenantId, byte status);
}
