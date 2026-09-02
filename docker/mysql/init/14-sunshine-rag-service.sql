-- sunshine-rag-service（rag-service :8400 · 库 sunshine_rag · 全量 v1）
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
    active_version  VARCHAR(14) NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_tenant_kb_doc (tenant_id, kb_id, doc_id)
);

CREATE TABLE document_version (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id       VARCHAR(32) NOT NULL,
    kb_id           VARCHAR(64) NOT NULL,
    doc_id          VARCHAR(128) NOT NULL,
    version         VARCHAR(14) NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'draft',
    parsed_markdown MEDIUMTEXT,
    storage_path    VARCHAR(512) NULL,
    chunk_count     INT NOT NULL DEFAULT 0,
    chunk_strategy  VARCHAR(32) NULL,
    chunk_params_json JSON NULL,
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
    target_version  VARCHAR(14) NULL,
    file_name       VARCHAR(512),
    mime_type       VARCHAR(128),
    source_type     VARCHAR(16) NULL,
    status          VARCHAR(24) NOT NULL DEFAULT 'parsing',
    progress_pct    DOUBLE NULL,
    progress_page   INT NULL,
    total_pages     INT NULL,
    source_object_key VARCHAR(512) NULL,
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

CREATE TABLE eval_suite (
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

CREATE TABLE eval_suite_item (
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

-- 默认知识库
INSERT INTO knowledge_base (tenant_id, kb_id, display_name, description, is_default, status)
VALUES ('default', 'default', '默认知识库', '系统默认知识库', 1, 'active');

-- 内置评测集元数据种子（条目 SSOT：docs/knowledge/eval_suite.json + scripts/rag_eval.py --sync）
INSERT INTO eval_suite
    (tenant_id, suite_key, display_name, description, kind, format, schema_version, storage, item_count, status)
VALUES
    ('default', 'sunshine-regression', 'corpus50 标准回归', '基于 corpus-50 全新语料的检索回归集', 'standard', 'json', 1, 'mysql', 0, 'active'),
    ('default', 'sunshine-adversarial', 'corpus50 难例对抗', '口语化改写难例（对齐 corpus-50）', 'standard', 'json', 1, 'mysql', 0, 'active'),
    ('default', 'sunshine-smoke', 'corpus50 冒烟门禁', '发布/切换配置用冒烟子集', 'standard', 'json', 1, 'mysql', 0, 'active')
ON DUPLICATE KEY UPDATE
    display_name = VALUES(display_name),
    description = VALUES(description),
    kind = VALUES(kind),
    format = VALUES(format),
    schema_version = VALUES(schema_version),
    storage = VALUES(storage),
    item_count = VALUES(item_count);

UPDATE eval_suite SET config_json=CAST('{"topK":[3,5,10],"minScore":0.48,"gates":{"recallAt3Min":0.90,"recallAt5Min":0.94,"mrrMin":0.85,"emptyRatePositiveMax":0.02,"emptyRateNegativeMin":0.0,"latencyP95MsMax":20000}}' AS JSON)
  WHERE tenant_id='default' AND suite_key='sunshine-regression';
UPDATE eval_suite SET config_json=CAST('{"topK":[3,5,10],"minScore":0.48,"gates":{"recallAt3Min":0.90,"recallAt5Min":0.88,"mrrMin":0.80,"emptyRatePositiveMax":0.02,"emptyRateNegativeMin":0.0,"latencyP95MsMax":20000}}' AS JSON)
  WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
UPDATE eval_suite SET config_json=CAST('{"topK":[3,5,10],"minScore":0.48,"gates":{"recallAt3Min":0.90,"recallAt5Min":0.94,"mrrMin":0.85,"emptyRatePositiveMax":0.02,"emptyRateNegativeMin":0.0,"latencyP95MsMax":20000}}' AS JSON)
  WHERE tenant_id='default' AND suite_key='sunshine-smoke';
DELETE FROM eval_suite_item WHERE suite_id=(SELECT id FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression');
DELETE FROM eval_suite_item WHERE suite_id=(SELECT id FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial');
DELETE FROM eval_suite_item WHERE suite_id=(SELECT id FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke');

-- default 知识库 RAG 配置 bundle（业务参数 SSOT；仅 seed 生效版，草稿由用户「复制为草稿」创建）
INSERT INTO rag_config_bundle (tenant_id, kb_id, created_at, updated_at)
VALUES ('default', 'default', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

SET @bundle_id = LAST_INSERT_ID();

INSERT INTO rag_config_version (bundle_id, version_no, status, payload_json, change_note, published_at)
VALUES (@bundle_id, 1, 'active', CAST('{"search": {"minScore": 0.48, "strategy": "hybrid+rerank", "rrfK": 60, "hybridPoolSize": 20, "defaultTopK": 3}, "rerank": {"enabled": true, "minScore": 0.25, "minRelevance": 0.25}, "rewrite": {"rag": {"enabled": true, "model": "deepseek-v4-flash", "systemPrompt": "你是企业知识库检索 query 优化助手。用户问题将用于向量/混合检索。\\n请补全域内关键词（制度、流程、报销、请假、差旅、考勤、青松假、网约车、安全、IT、账号权限、法务、合同、行政、访客、PMO、变更窗口等），标准化专有名词表述。\\n保留原意，不要编造事实；若已足够清晰则轻微润色即可。\\n只输出 JSON：{\\"query\\":\\"优化后的检索 query\\"}，不要 markdown 或其他文字。"}, "hyde": {"enabled": true, "model": "deepseek-v4-flash", "maxChars": 480, "systemPrompt": "你是企业知识库 HyDE 助手。根据用户问题，写一段**可能出现在企业制度/流程文档中**的中文段落，\\n用于向量检索匹配；不要写问答体，不要写「根据…规定」等元叙述，直接写制度条文式正文。\\n只引用常见域内概念（报销、差旅、请假、考勤、青松假、网约车、安全、IT、法务、行政、PMO、审批等），**禁止编造**具体金额/日期/人名。\\n只输出 JSON：{\\"document\\":\\"假想文档段落\\"}，不要 markdown 或其他文字。"}, "emptyRecall": {"enabled": true, "model": "deepseek-v4-flash", "maxAlternatives": 2, "systemPrompt": "你是企业知识库检索 query 改写助手。用户原始问题在向量/混合检索中零命中。\\n请生成 %d 个不同表述的中文检索 query，用于二次检索。\\n要求：\\n- 必须保留原问题的核心业务领域与关键名词，禁止改问无关主题；\\n- 仅在同领域内补充「制度」「管理办法」「流程规范」等同义表述；\\n- 不要编造事实。\\n只输出 JSON：{\\"queries\\":[\\"改写1\\",\\"改写2\\"]}，不要 markdown 或其他文字。"}}}' AS JSON), 'docker-init', CURRENT_TIMESTAMP);

UPDATE rag_config_bundle
SET active_published_version_id = LAST_INSERT_ID(),
    draft_version_id = NULL
WHERE id = @bundle_id;
