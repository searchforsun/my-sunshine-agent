package com.sunshine.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "sys_user")
@Getter
@Setter
public class UserEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 32, unique = true)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 128)
    private String passwordHash;

    @Column(length = 64)
    private String nickname;

    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId = "default";

    @Column(nullable = false, columnDefinition = "TINYINT")
    private Byte status = 1;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
