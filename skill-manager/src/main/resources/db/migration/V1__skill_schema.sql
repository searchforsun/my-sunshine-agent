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
    storage_path    VARCHAR(512),
    status          VARCHAR(24) NOT NULL DEFAULT 'published',
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_skill_version (skill_id, version),
    CONSTRAINT fk_skill_version_def FOREIGN KEY (skill_id) REFERENCES skill_definition (id)
);
