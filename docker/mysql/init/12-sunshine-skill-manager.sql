-- sunshine-skill-manager（skill-manager :8225 · 库 sunshine_skill · 全量 v1）
USE sunshine_skill;

CREATE TABLE skill_definition (
    id              VARCHAR(64) PRIMARY KEY,
    display_name    VARCHAR(128) NOT NULL,
    description     VARCHAR(512),
    enabled         TINYINT(1) NOT NULL DEFAULT 1,
    active_version  INT NOT NULL DEFAULT 1,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE skill_version (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    skill_id        VARCHAR(64) NOT NULL,
    version         INT NOT NULL,
    system_overlay  MEDIUMTEXT NOT NULL,
    tools_json      VARCHAR(512) NOT NULL DEFAULT '[]',
    max_iters       INT NOT NULL DEFAULT 4,
    side_effect     VARCHAR(32) NOT NULL DEFAULT 'read',
    sandbox         VARCHAR(32) NOT NULL DEFAULT 'none',
    sandbox_policy_json JSON NULL COMMENT 'sandbox_policy',
    references_json VARCHAR(1024) NOT NULL DEFAULT '[]',
    scripts_json    VARCHAR(1024) NOT NULL DEFAULT '[]',
    storage_path    VARCHAR(512),
    status          VARCHAR(24) NOT NULL DEFAULT 'published',
    maintainer      VARCHAR(64) NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_skill_version (skill_id, version),
    CONSTRAINT fk_skill_version_def FOREIGN KEY (skill_id) REFERENCES skill_definition (id)
);

-- Skill 种子 SSOT：docs/skills/ + scripts/sync_enterprise_skills.py（不自动入库）
