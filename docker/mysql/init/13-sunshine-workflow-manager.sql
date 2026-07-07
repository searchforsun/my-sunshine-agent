-- sunshine-workflow-manager（workflow-manager :8230）
USE sunshine_workflow;

CREATE TABLE workflow_definition (
    tenant_id       VARCHAR(64) NOT NULL DEFAULT 'default',
    id              VARCHAR(64) NOT NULL,
    display_name    VARCHAR(128) NOT NULL,
    description     VARCHAR(512),
    mode            VARCHAR(24) NOT NULL DEFAULT 'workflow',
    enabled         TINYINT(1) NOT NULL DEFAULT 0,
    active_version  INT NOT NULL DEFAULT 0,
    source          VARCHAR(16) NOT NULL DEFAULT 'studio',
    maintainer      VARCHAR(64),
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, id)
);

CREATE TABLE workflow_version (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id       VARCHAR(64) NOT NULL,
    workflow_id     VARCHAR(64) NOT NULL,
    version         INT NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'draft',
    plan_json       MEDIUMTEXT NOT NULL,
    catalog_meta    JSON,
    published_at    TIMESTAMP NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_workflow_version (tenant_id, workflow_id, version),
    CONSTRAINT fk_workflow_version_def FOREIGN KEY (tenant_id, workflow_id)
        REFERENCES workflow_definition (tenant_id, id)
);
