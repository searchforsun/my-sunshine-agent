-- 内置评测集（表结构 SSOT；条目种子见 11-sunshine-rag-eval-suite-seed.sql）
USE sunshine_rag;

CREATE TABLE IF NOT EXISTS eval_suite (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id       VARCHAR(32) NOT NULL,
    suite_key       VARCHAR(64) NOT NULL,
    display_name    VARCHAR(128) NOT NULL,
    description     VARCHAR(512) NULL,
    kind            VARCHAR(16) NOT NULL DEFAULT 'standard',
    format          VARCHAR(8) NOT NULL DEFAULT 'json',
    schema_version  INT NOT NULL DEFAULT 1,
    storage         VARCHAR(16) NOT NULL DEFAULT 'mysql',
    content_ref     VARCHAR(512) NULL,
    hooks_json      JSON,
    config_json     JSON NULL,
    item_count      INT NOT NULL DEFAULT 0,
    status          VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_tenant_suite (tenant_id, suite_key)
);

CREATE TABLE IF NOT EXISTS eval_suite_item (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    suite_id            BIGINT NOT NULL,
    item_key            VARCHAR(64) NOT NULL,
    sort_order          INT NOT NULL DEFAULT 0,
    query_text          VARCHAR(1024) NOT NULL,
    item_type           VARCHAR(16) NOT NULL DEFAULT 'positive',
    relevant_doc_ids    JSON NOT NULL,
    relevant_keywords   JSON NULL,
    category            VARCHAR(32) NULL,
    expect_empty        TINYINT(1) NOT NULL DEFAULT 0,
    status              VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_suite_item (suite_id, item_key),
    KEY idx_suite_order (suite_id, sort_order),
    CONSTRAINT fk_suite_item_suite FOREIGN KEY (suite_id) REFERENCES eval_suite (id) ON DELETE CASCADE
);

INSERT INTO eval_suite
    (tenant_id, suite_key, display_name, description, kind, format, schema_version, storage, item_count, status)
VALUES
    ('default', 'sunshine-regression', '标准回归', '标准检索回归评测集', 'standard', 'json', 1, 'mysql', 123, 'active'),
    ('default', 'sunshine-adversarial', '难例对抗', '难例对抗评测集', 'standard', 'json', 1, 'mysql', 46, 'active'),
    ('default', 'sunshine-smoke', '冒烟门禁', '冒烟门禁评测集（发布/切换配置用）', 'standard', 'json', 1, 'mysql', 50, 'active')
ON DUPLICATE KEY UPDATE
    display_name = VALUES(display_name),
    description = VALUES(description),
    kind = VALUES(kind),
    format = VALUES(format),
    schema_version = VALUES(schema_version),
    storage = VALUES(storage),
    item_count = VALUES(item_count);
