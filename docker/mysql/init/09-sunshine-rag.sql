-- Sunshine 业务库：sunshine_rag（RAG 知识库工作台 MySQL SSOT）
USE sunshine_rag;

-- V1__rag_schema.sql
CREATE TABLE knowledge_base (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id       VARCHAR(32) NOT NULL,
    kb_id           VARCHAR(64) NOT NULL,
    display_name    VARCHAR(128) NOT NULL,
    description     VARCHAR(512),
    is_default      TINYINT(1) NOT NULL DEFAULT 0,
    status          VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_tenant_kb (tenant_id, kb_id)
);

CREATE TABLE document (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id       VARCHAR(32) NOT NULL,
    kb_id           VARCHAR(64) NOT NULL,
    doc_id          VARCHAR(128) NOT NULL,
    display_name    VARCHAR(256) NOT NULL,
    source_type     VARCHAR(32) NOT NULL DEFAULT 'markdown',
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_tenant_kb_doc (tenant_id, kb_id, doc_id)
);

CREATE TABLE document_version (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id       VARCHAR(32) NOT NULL,
    kb_id           VARCHAR(64) NOT NULL,
    doc_id          VARCHAR(128) NOT NULL,
    version         INT NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'draft',
    parsed_markdown MEDIUMTEXT,
    chunk_count     INT NOT NULL DEFAULT 0,
    ingest_job_id   BIGINT,
    published_at    TIMESTAMP NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_doc_version (tenant_id, kb_id, doc_id, version)
);

CREATE TABLE kb_config_override (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id       VARCHAR(32) NOT NULL,
    kb_id           VARCHAR(64) NOT NULL,
    override_json   JSON NOT NULL,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_kb_override (tenant_id, kb_id)
);

CREATE TABLE config_draft (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id       VARCHAR(32) NOT NULL,
    scope           VARCHAR(64) NOT NULL,
    payload_json    JSON NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'draft',
    created_by      VARCHAR(64),
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at    TIMESTAMP NULL
);

CREATE TABLE ingest_job (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id       VARCHAR(32) NOT NULL,
    kb_id           VARCHAR(64) NOT NULL,
    doc_id          VARCHAR(128),
    file_name       VARCHAR(512),
    mime_type       VARCHAR(128),
    status          VARCHAR(24) NOT NULL DEFAULT 'parsing',
    confidence      DOUBLE,
    parsed_markdown MEDIUMTEXT,
    error_msg       VARCHAR(1024),
    auto_pass       TINYINT(1) NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE eval_job (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id       VARCHAR(32) NOT NULL,
    kb_id           VARCHAR(64) NOT NULL DEFAULT 'default',
    suite           VARCHAR(32) NOT NULL,
    config_snapshot_json JSON,
    status          VARCHAR(16) NOT NULL DEFAULT 'pending',
    report_id       BIGINT,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at     TIMESTAMP NULL
);

CREATE TABLE eval_report (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id          BIGINT NOT NULL,
    recall_at_5     DOUBLE,
    mrr             DOUBLE,
    delta_json      JSON,
    baseline_recall_at_5 DOUBLE,
    passed_gate     TINYINT(1),
    report_md_path  VARCHAR(512),
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_eval_report_job FOREIGN KEY (job_id) REFERENCES eval_job (id)
);

CREATE TABLE badcase (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id       VARCHAR(32) NOT NULL,
    kb_id           VARCHAR(64) NOT NULL DEFAULT 'default',
    query           VARCHAR(512) NOT NULL,
    relevant_doc_ids_json JSON,
    notes           VARCHAR(1024),
    source          VARCHAR(64),
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO knowledge_base (tenant_id, kb_id, display_name, description, is_default, status)
VALUES ('default', 'default', '默认知识库', '系统默认知识库', 1, 'active');

-- Flyway 基线（与 rag-service classpath db/migration 校验一致）
CREATE TABLE IF NOT EXISTS flyway_schema_history (
    installed_rank INT NOT NULL,
    version VARCHAR(50),
    description VARCHAR(200) NOT NULL,
    type VARCHAR(20) NOT NULL,
    script VARCHAR(1000) NOT NULL,
    checksum INT,
    installed_by VARCHAR(100) NOT NULL,
    installed_on TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    execution_time INT NOT NULL,
    success TINYINT(1) NOT NULL,
    PRIMARY KEY (installed_rank),
    INDEX flyway_schema_history_s_idx (success)
);
DELETE FROM flyway_schema_history;
INSERT INTO flyway_schema_history VALUES
(1, '1', 'rag schema', 'SQL', 'V1__rag_schema.sql', 57158598, 'docker-init', NOW(), 0, 1);
