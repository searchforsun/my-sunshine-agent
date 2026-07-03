-- Sunshine 业务库：sunshine_rag（RAG 知识库工作台 MySQL SSOT）
-- 由 docker-entrypoint-initdb.d 首次初始化；增量变更请新增编号更大的 09+ 脚本
USE sunshine_rag;

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

CREATE TABLE rag_config_bundle (
    id                              BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id                       VARCHAR(32) NOT NULL,
    kb_id                           VARCHAR(64) NOT NULL,
    draft_version_id                BIGINT,
    active_published_version_id     BIGINT,
    created_at                      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_bundle_tenant_kb (tenant_id, kb_id)
);

CREATE TABLE rag_config_version (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    bundle_id           BIGINT NOT NULL,
    version_no          INT NOT NULL,
    status              VARCHAR(16) NOT NULL,
    payload_json        JSON NOT NULL,
    change_note         VARCHAR(512),
    created_by          VARCHAR(64),
    publish_eval_job_id BIGINT,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at        TIMESTAMP NULL,
    UNIQUE KEY uk_bundle_version_no (bundle_id, version_no),
    CONSTRAINT fk_version_bundle FOREIGN KEY (bundle_id) REFERENCES rag_config_bundle (id)
);

CREATE TABLE eval_job (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id            VARCHAR(32) NOT NULL,
    kb_id                VARCHAR(64) NOT NULL DEFAULT 'default',
    suite                VARCHAR(32) NOT NULL,
    suite_id             BIGINT NULL,
    config_snapshot_json JSON,
    config_version_id    BIGINT NULL,
    config_mode          VARCHAR(16) NULL,
    status               VARCHAR(16) NOT NULL DEFAULT 'pending',
    total_items          INT NULL,
    processed_items      INT NOT NULL DEFAULT 0,
    report_id            BIGINT,
    report_object_key    VARCHAR(512) NULL,
    created_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at          TIMESTAMP NULL
);

CREATE TABLE eval_report (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id               BIGINT NOT NULL,
    recall_at_5          DOUBLE,
    mrr                  DOUBLE,
    delta_json           JSON,
    summary_json         JSON NULL,
    failed_samples_json  JSON NULL,
    suggestions_json     JSON NULL,
    baseline_recall_at_5 DOUBLE,
    passed_gate          TINYINT(1),
    report_md_path       VARCHAR(512),
    report_object_key    VARCHAR(512) NULL,
    created_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_eval_report_job FOREIGN KEY (job_id) REFERENCES eval_job (id)
);

INSERT INTO knowledge_base (tenant_id, kb_id, display_name, description, is_default, status)
VALUES ('default', 'default', '默认知识库', '系统默认知识库', 1, 'active');
