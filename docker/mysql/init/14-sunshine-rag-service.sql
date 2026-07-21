-- sunshine-rag-service（rag-service :8400 · 库 sunshine_rag · SSOT 合并）
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

-- 内置评测集（表结构 + 元数据种子）
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

-- 内置评测集条目种子
USE sunshine_rag;

-- sunshine-regression
UPDATE eval_suite SET kind='standard', format='json', storage='mysql', content_ref=NULL,
  display_name='标准回归', description='标准检索回归评测集（不含对抗难例）', schema_version=1,
  config_json=CAST('{"topK":[3,5,10],"minScore":0.48,"gates":{"recallAt3Min":0.95,"recallAt5Min":0.98,"mrrMin":0.92,"emptyRatePositiveMax":0.0,"emptyRateNegativeMin":0.95,"latencyP95MsMax":500}}' AS JSON), item_count=123
  WHERE tenant_id='default' AND suite_key='sunshine-regression';
DELETE FROM eval_suite_item WHERE suite_id=(SELECT id FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression');
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q001', 0, '年假可以请几天', 'positive', CAST('["leave-policy-v1"]' AS JSON), CAST('["年假", "工龄"]' AS JSON), 'leave', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q002', 1, '病假需要什么证明材料', 'positive', CAST('["leave-policy-v1"]' AS JSON), CAST('["病假", "证明", "材料"]' AS JSON), 'leave', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q003', 2, '请假要提前多久申请', 'positive', CAST('["leave-policy-v1"]' AS JSON), CAST('["提前", "申请"]' AS JSON), 'leave', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q004', 3, '事假有没有工资', 'positive', CAST('["leave-policy-v1"]' AS JSON), CAST('["事假", "无薪"]' AS JSON), 'leave', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q005', 4, '婚假有多少天', 'positive', CAST('["leave-policy-v1"]' AS JSON), CAST('["婚假"]' AS JSON), 'leave', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q006', 5, '产假陪产假怎么休', 'positive', CAST('["leave-policy-v1"]' AS JSON), CAST('["产假", "陪产假"]' AS JSON), 'leave', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q007', 6, '直属主管审批职责是什么', 'positive', CAST('["leave-policy-v1"]' AS JSON), CAST('["直属主管", "审批"]' AS JSON), 'process', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q008', 7, 'HR在请假流程中负责什么', 'positive', CAST('["leave-policy-v1"]' AS JSON), CAST('["HR", "假期余额"]' AS JSON), 'process', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q009', 8, '三天以上请假谁审批', 'positive', CAST('["leave-policy-v1"]' AS JSON), CAST('["部门负责人", "3"]' AS JSON), 'process', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q010', 9, '销假是什么意思怎么操作', 'positive', CAST('["leave-policy-v1"]' AS JSON), CAST('["销假"]' AS JSON), 'process', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q011', 10, '加班调休余额在哪里查', 'positive', CAST('["attendance-policy-v1", "leave-policy-v1"]' AS JSON), CAST('["调休", "余额", "OA"]' AS JSON), 'leave', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q012', 11, '丧假可以请几天', 'positive', CAST('["leave-policy-v1"]' AS JSON), CAST('["丧假"]' AS JSON), 'leave', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_neg_001', 12, '公司股票期权怎么兑现', 'negative', CAST('[]' AS JSON), CAST('[]' AS JSON), 'negative', 1 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_neg_002', 13, '公司股权激励行权如何套现', 'negative', CAST('[]' AS JSON), CAST('[]' AS JSON), 'negative', 1 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q013', 14, '市内交通报销上限多少', 'positive', CAST('["expense-policy-v1"]' AS JSON), CAST('["市内交通", "200"]' AS JSON), 'expense', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q014', 15, '差旅住宿一晚能报多少钱', 'positive', CAST('["expense-policy-v1"]' AS JSON), CAST('["差旅", "住宿", "600"]' AS JSON), 'expense', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q015', 16, '餐饮招待费人均标准', 'positive', CAST('["expense-policy-v1"]' AS JSON), CAST('["餐饮", "150"]' AS JSON), 'expense', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q016', 17, '报销需要什么样的发票', 'positive', CAST('["expense-policy-v1"]' AS JSON), CAST('["发票", "增值税"]' AS JSON), 'expense', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q017', 18, '超过五千的报销谁审批', 'positive', CAST('["expense-policy-v1"]' AS JSON), CAST('["5000", "CFO"]' AS JSON), 'expense', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q018', 19, '拆单报销是否允许', 'positive', CAST('["expense-policy-v1"]' AS JSON), CAST('["拆单", "禁止"]' AS JSON), 'expense', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q019', 20, '私人消费能报销吗', 'positive', CAST('["expense-policy-v1"]' AS JSON), CAST('["私人", "禁止"]' AS JSON), 'expense', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q020', 21, '报销审批流程有几步', 'positive', CAST('["expense-policy-v1"]' AS JSON), CAST('["审批", "财务复核"]' AS JSON), 'expense', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q021', 22, '电子发票报销要注意什么', 'positive', CAST('["expense-policy-v1", "invoice-faq-v1"]' AS JSON), CAST('["电子发票", "税号"]' AS JSON), 'expense', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q022', 23, '办公用品采购怎么报销', 'positive', CAST('["expense-policy-v1"]' AS JSON), CAST('["办公用品"]' AS JSON), 'expense', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q023', 24, '虚假发票报销后果', 'positive', CAST('["expense-policy-v1"]' AS JSON), CAST('["虚假发票"]' AS JSON), 'expense', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q024', 25, '因公交通费谁审批', 'positive', CAST('["expense-policy-v1"]' AS JSON), CAST('["直属主管"]' AS JSON), 'expense', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q025', 26, '迟到几次算旷工', 'positive', CAST('["attendance-policy-v1"]' AS JSON), CAST('["迟到", "旷工"]' AS JSON), 'attendance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q026', 27, '加班需要提前申请吗', 'positive', CAST('["attendance-policy-v1"]' AS JSON), CAST('["加班", "申请"]' AS JSON), 'attendance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q027', 28, '周末加班怎么算调休', 'positive', CAST('["attendance-policy-v1"]' AS JSON), CAST('["周末", "调休"]' AS JSON), 'attendance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q028', 29, '弹性上下班时间规定', 'positive', CAST('["attendance-policy-v1"]' AS JSON), CAST('["弹性"]' AS JSON), 'attendance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q029', 30, '忘打卡怎么补签', 'positive', CAST('["attendance-policy-v1"]' AS JSON), CAST('["补签", "打卡"]' AS JSON), 'attendance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q030', 31, '外出办公如何登记考勤', 'positive', CAST('["attendance-policy-v1"]' AS JSON), CAST('["外出"]' AS JSON), 'attendance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q031', 32, '夜班补贴标准', 'positive', CAST('["attendance-policy-v1"]' AS JSON), CAST('["夜班"]' AS JSON), 'attendance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q032', 33, '法定节假日加班工资', 'positive', CAST('["attendance-policy-v1"]' AS JSON), CAST('["法定节假日"]' AS JSON), 'attendance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q033', 34, '考勤异常申诉找谁', 'positive', CAST('["attendance-policy-v1"]' AS JSON), CAST('["申诉", "HR"]' AS JSON), 'attendance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q034', 35, '远程办公考勤要求', 'positive', CAST('["attendance-policy-v1"]' AS JSON), CAST('["远程"]' AS JSON), 'attendance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q035', 36, '新员工入职第一天做什么', 'positive', CAST('["onboarding-policy-v1"]' AS JSON), CAST('["入职", "第一天"]' AS JSON), 'onboarding', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q036', 37, '入职需要带哪些材料', 'positive', CAST('["onboarding-policy-v1"]' AS JSON), CAST('["材料", "身份证"]' AS JSON), 'onboarding', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q037', 38, '试用期多长时间', 'positive', CAST('["onboarding-policy-v1"]' AS JSON), CAST('["试用期"]' AS JSON), 'onboarding', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q038', 39, '入职培训有哪些环节', 'positive', CAST('["onboarding-policy-v1"]' AS JSON), CAST('["培训"]' AS JSON), 'onboarding', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q039', 40, '工牌和账号什么时候开通', 'positive', CAST('["onboarding-policy-v1"]' AS JSON), CAST('["工牌", "账号"]' AS JSON), 'onboarding', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q040', 41, '入职导师制度是什么', 'positive', CAST('["onboarding-policy-v1"]' AS JSON), CAST('["导师"]' AS JSON), 'onboarding', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q041', 42, '劳动合同什么时候签', 'positive', CAST('["onboarding-policy-v1"]' AS JSON), CAST('["劳动合同"]' AS JSON), 'onboarding', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q042', 43, '入职体检要求', 'positive', CAST('["onboarding-policy-v1"]' AS JSON), CAST('["体检"]' AS JSON), 'onboarding', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q043', 44, '部门经理审批额度上限', 'positive', CAST('["finance-approval-v1"]' AS JSON), CAST('["部门经理", "额度"]' AS JSON), 'finance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q044', 45, 'CFO审批什么金额以上的单据', 'positive', CAST('["finance-approval-v1"]' AS JSON), CAST('["CFO"]' AS JSON), 'finance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q045', 46, '财务复核的职责是什么', 'positive', CAST('["finance-approval-v1"]' AS JSON), CAST('["财务复核"]' AS JSON), 'finance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q046', 47, '预算外支出谁批准', 'positive', CAST('["finance-approval-v1", "budget-policy-v1"]' AS JSON), CAST('["预算外"]' AS JSON), 'finance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q047', 48, '采购付款审批矩阵', 'positive', CAST('["finance-approval-v1"]' AS JSON), CAST('["采购", "付款"]' AS JSON), 'finance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q048', 49, '差旅费审批权限分级', 'positive', CAST('["finance-approval-v1", "expense-policy-v1"]' AS JSON), CAST('["差旅", "审批"]' AS JSON), 'finance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q049', 50, '增值税专用发票和普通发票区别', 'positive', CAST('["invoice-faq-v1"]' AS JSON), CAST('["增值税", "专用"]' AS JSON), 'finance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q050', 51, '发票抬头必须写公司全称吗', 'positive', CAST('["invoice-faq-v1"]' AS JSON), CAST('["抬头"]' AS JSON), 'finance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q051', 52, '电子发票如何验真', 'positive', CAST('["invoice-faq-v1"]' AS JSON), CAST('["验真"]' AS JSON), 'finance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q052', 53, '报销发票税率有什么要求', 'positive', CAST('["invoice-faq-v1"]' AS JSON), CAST('["税率"]' AS JSON), 'finance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q053', 54, '丢失发票还能报销吗', 'positive', CAST('["invoice-faq-v1"]' AS JSON), CAST('["丢失"]' AS JSON), 'finance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q054', 55, '个人抬头发票能否报销', 'positive', CAST('["invoice-faq-v1"]' AS JSON), CAST('["个人抬头"]' AS JSON), 'finance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q055', 56, '部门预算什么时候编制', 'positive', CAST('["budget-policy-v1"]' AS JSON), CAST('["编制"]' AS JSON), 'finance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q056', 57, '预算调剂需要谁批准', 'positive', CAST('["budget-policy-v1"]' AS JSON), CAST('["调剂"]' AS JSON), 'finance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q057', 58, '季度预算执行率怎么考核', 'positive', CAST('["budget-policy-v1"]' AS JSON), CAST('["执行率"]' AS JSON), 'finance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q058', 59, '项目预算超支怎么办', 'positive', CAST('["budget-policy-v1"]' AS JSON), CAST('["超支"]' AS JSON), 'finance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q059', 60, '预算内采购流程', 'positive', CAST('["budget-policy-v1"]' AS JSON), CAST('["采购"]' AS JSON), 'finance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q060', 61, '年度预算调整窗口期', 'positive', CAST('["budget-policy-v1"]' AS JSON), CAST('["调整"]' AS JSON), 'finance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q061', 62, '远程办公算不算请假', 'positive', CAST('["remote-policy-v1"]' AS JSON), CAST('["不算", "请假"]' AS JSON), 'remote', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q062', 63, '混合办公每周几天可以远程', 'positive', CAST('["remote-policy-v1"]' AS JSON), CAST('["1-2"]' AS JSON), 'remote', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q063', 64, '远程办公考勤怎么打卡', 'positive', CAST('["remote-policy-v1"]' AS JSON), CAST('["远程签到"]' AS JSON), 'remote', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q064', 65, '长期远程办公谁审批', 'positive', CAST('["remote-policy-v1"]' AS JSON), CAST('["部门负责人"]' AS JSON), 'remote', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q065', 66, '试用期可以远程办公吗', 'positive', CAST('["remote-policy-v1"]' AS JSON), CAST('["首月"]' AS JSON), 'remote', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q066', 67, '远程办公期间能去外地吗', 'positive', CAST('["remote-policy-v1"]' AS JSON), CAST('["24小时"]' AS JSON), 'remote', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q067', 68, '远程办公和出差有什么区别', 'positive', CAST('["remote-policy-v1", "travel-policy-v1"]' AS JSON), CAST('["出差"]' AS JSON), 'remote', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q068', 69, '远程办公日加班怎么申请', 'positive', CAST('["remote-policy-v1", "attendance-policy-v1"]' AS JSON), CAST('["加班"]' AS JSON), 'remote', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q069', 70, '公司账号密码多久要换一次', 'positive', CAST('["security-policy-v1"]' AS JSON), CAST('["90天"]' AS JSON), 'security', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q070', 71, '能不能把公司代码推到GitHub', 'positive', CAST('["security-policy-v1"]' AS JSON), CAST('["禁止"]' AS JSON), 'security', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q071', 72, '客户数据可以下载到笔记本本地吗', 'positive', CAST('["security-policy-v1"]' AS JSON), CAST('["禁止"]' AS JSON), 'security', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q072', 73, 'VPN连不上内网怎么办', 'positive', CAST('["security-policy-v1"]' AS JSON), CAST('["it@company.com"]' AS JSON), 'security', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q073', 74, '离职之后还能用公司邮箱吗', 'positive', CAST('["security-policy-v1"]' AS JSON), CAST('["冻结"]' AS JSON), 'security', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q074', 75, '公司数据分几个保密级别', 'positive', CAST('["security-policy-v1"]' AS JSON), CAST('["L1", "L4"]' AS JSON), 'security', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q075', 76, '信息安全考试多少分及格', 'positive', CAST('["security-policy-v1", "onboarding-policy-v1"]' AS JSON), CAST('[80]' AS JSON), 'security', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q076', 77, '发现同事泄露敏感数据怎么举报', 'positive', CAST('["security-policy-v1"]' AS JSON), CAST('["security@company.com"]' AS JSON), 'security', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q077', 78, '一线城市出差住宿标准是多少', 'positive', CAST('["travel-policy-v1"]' AS JSON), CAST('[600]' AS JSON), 'travel', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q078', 79, '出差超过7天需要谁审批', 'positive', CAST('["travel-policy-v1", "finance-approval-v1"]' AS JSON), CAST('["CFO"]' AS JSON), 'travel', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q079', 80, '差旅费报销要在几天内提交', 'positive', CAST('["travel-policy-v1"]' AS JSON), CAST('["10个工作日"]' AS JSON), 'travel', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q080', 81, '客户招待餐能不能算在差旅费里', 'positive', CAST('["travel-policy-v1"]' AS JSON), CAST('["不能"]' AS JSON), 'travel', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q081', 82, '普通员工出差能报商务舱吗', 'positive', CAST('["travel-policy-v1"]' AS JSON), CAST('["经济舱"]' AS JSON), 'travel', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q082', 83, '出差遇到周末住宿费能报销吗', 'positive', CAST('["travel-policy-v1"]' AS JSON), CAST('["周末"]' AS JSON), 'travel', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q083', 84, '国际出差要提前多久申请', 'positive', CAST('["travel-policy-v1"]' AS JSON), CAST('["15日"]' AS JSON), 'travel', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q084', 85, '差旅费和日常打车费能混在一张单子报吗', 'positive', CAST('["travel-policy-v1", "expense-policy-v1"]' AS JSON), CAST('["不能"]' AS JSON), 'travel', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q085', 86, '出差预算不够了要先走什么流程', 'positive', CAST('["travel-policy-v1", "budget-policy-v1"]' AS JSON), CAST('["调剂"]' AS JSON), 'travel', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q086', 87, '远程办公违反信息安全规定会怎样', 'positive', CAST('["remote-policy-v1", "security-policy-v1"]' AS JSON), CAST('["违规"]' AS JSON), 'security', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q087', 88, '新员工入职信息安全培训什么时候完成', 'positive', CAST('["onboarding-policy-v1", "security-policy-v1"]' AS JSON), CAST('["第二天"]' AS JSON), 'onboarding', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q088', 89, '预算内的出差费用审批走哪张矩阵', 'positive', CAST('["travel-policy-v1", "finance-approval-v1"]' AS JSON), CAST('["审批"]' AS JSON), 'finance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q089', 90, '出差期间想请半天年假怎么操作', 'positive', CAST('["scenario-guide-v1", "leave-policy-v1", "travel-policy-v1"]' AS JSON), CAST('["半天年假"]' AS JSON), 'multihop', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q090', 91, '项目预算超支了还能安排出差吗', 'positive', CAST('["scenario-guide-v1", "budget-policy-v1", "travel-policy-v1"]' AS JSON), CAST('["超支"]' AS JSON), 'multihop', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q091', 92, '远程办公时生病了要先销远程单还是先请假', 'positive', CAST('["scenario-guide-v1", "remote-policy-v1", "leave-policy-v1"]' AS JSON), CAST('["病假"]' AS JSON), 'multihop', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q092', 93, '试用期员工能申请出国出差吗', 'positive', CAST('["scenario-guide-v1", "onboarding-policy-v1", "travel-policy-v1"]' AS JSON), CAST('["国际出差"]' AS JSON), 'multihop', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q093', 94, '出差回来超过10个工作日还能报销吗', 'positive', CAST('["scenario-guide-v1", "travel-policy-v1", "expense-policy-v1"]' AS JSON), CAST('["10个工作日"]' AS JSON), 'multihop', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q094', 95, '加班到很晚第二天能直接远程办公吗', 'positive', CAST('["scenario-guide-v1", "attendance-policy-v1", "remote-policy-v1"]' AS JSON), CAST('["事前"]' AS JSON), 'multihop', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q095', 96, '报销发票抬头错了同时预算不够怎么办', 'positive', CAST('["scenario-guide-v1", "invoice-faq-v1", "budget-policy-v1"]' AS JSON), CAST('["发票", "预算"]' AS JSON), 'multihop', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q096', 97, '预算调剂后采购出差用品走什么审批', 'positive', CAST('["scenario-guide-v1", "budget-policy-v1", "finance-approval-v1"]' AS JSON), CAST('["采购"]' AS JSON), 'multihop', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q097', 98, '长期远程办公的人年假怎么申请', 'positive', CAST('["scenario-guide-v1", "remote-policy-v1", "leave-policy-v1"]' AS JSON), CAST('["年假"]' AS JSON), 'multihop', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q098', 99, '出差能用个人笔记本处理客户数据吗', 'positive', CAST('["scenario-guide-v1", "security-policy-v1", "travel-policy-v1"]' AS JSON), CAST('["禁止"]' AS JSON), 'multihop', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q099', 100, '新员工没签劳动合同能出差吗', 'positive', CAST('["scenario-guide-v1", "onboarding-policy-v1", "travel-policy-v1"]' AS JSON), CAST('["劳动合同"]' AS JSON), 'multihop', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q100', 101, '什么金额的出差报销需要CFO审批', 'positive', CAST('["scenario-guide-v1", "finance-approval-v1", "travel-policy-v1"]' AS JSON), CAST('["CFO"]' AS JSON), 'multihop', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q101', 102, '法定节假日远程办公算不算加班', 'positive', CAST('["scenario-guide-v1", "remote-policy-v1", "attendance-policy-v1"]' AS JSON), CAST('["三倍工资"]' AS JSON), 'multihop', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q102', 103, '出差住宿超标没事前批准能报多少', 'positive', CAST('["scenario-guide-v1", "travel-policy-v1", "expense-policy-v1"]' AS JSON), CAST('["标准内"]' AS JSON), 'multihop', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q103', 104, '离职前没报销的差旅和年假怎么处理', 'positive', CAST('["scenario-guide-v1", "leave-policy-v1", "travel-policy-v1"]' AS JSON), CAST('["离职"]' AS JSON), 'multihop', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q104', 105, '预算内采购和差旅报销审批人一样吗', 'positive', CAST('["scenario-guide-v1", "budget-policy-v1", "travel-policy-v1"]' AS JSON), CAST('["不一定"]' AS JSON), 'multihop', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q105', 106, '远程办公违反考勤又泄露代码怎么处理', 'positive', CAST('["scenario-guide-v1", "remote-policy-v1", "security-policy-v1"]' AS JSON), CAST('["并查"]' AS JSON), 'multihop', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q106', 107, '病假三天以上要几位领导审批', 'positive', CAST('["scenario-guide-v1", "leave-policy-v1"]' AS JSON), CAST('["部门负责人"]' AS JSON), 'multihop', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q107', 108, '国际出差和国内出差审批有什么不同', 'positive', CAST('["scenario-guide-v1", "travel-policy-v1", "finance-approval-v1"]' AS JSON), CAST('["15日"]' AS JSON), 'multihop', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q108', 109, '信息安全考试没过能出差见客户吗', 'positive', CAST('["scenario-guide-v1", "onboarding-policy-v1", "security-policy-v1"]' AS JSON), CAST('[80]' AS JSON), 'multihop', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q109', 110, '出差周末加班调休和差旅餐补能同时拿吗', 'positive', CAST('["scenario-guide-v1", "travel-policy-v1", "attendance-policy-v1"]' AS JSON), CAST('["餐补"]' AS JSON), 'multihop', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q110', 111, '电子发票验真失败出差费用还能报吗', 'positive', CAST('["scenario-guide-v1", "invoice-faq-v1", "travel-policy-v1"]' AS JSON), CAST('["验真"]' AS JSON), 'multihop', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q111', 112, '直属主管批请假和批报销的金额上限各是多少', 'positive', CAST('["scenario-guide-v1", "finance-approval-v1", "leave-policy-v1"]' AS JSON), CAST('[2000]' AS JSON), 'multihop', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q112', 113, '紧急出差没做预算调剂就订票能报销吗', 'positive', CAST('["scenario-guide-v1", "travel-policy-v1", "budget-policy-v1"]' AS JSON), CAST('["不予报销"]' AS JSON), 'multihop', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_neg_003', 114, '如何投资比特币', 'negative', CAST('[]' AS JSON), CAST('[]' AS JSON), 'negative', 1 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_neg_004', 115, '公司上市代码是多少', 'negative', CAST('[]' AS JSON), CAST('[]' AS JSON), 'negative', 1 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_neg_005', 116, '帮我写一首唐诗', 'negative', CAST('[]' AS JSON), CAST('[]' AS JSON), 'negative', 1 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_neg_006', 117, '明天上海天气怎么样', 'negative', CAST('[]' AS JSON), CAST('[]' AS JSON), 'negative', 1 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_neg_007', 118, '公司食堂今天午餐有什么菜', 'negative', CAST('[]' AS JSON), CAST('[]' AS JSON), 'negative', 1 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_neg_008', 119, '个人护照加急怎么办理', 'negative', CAST('[]' AS JSON), CAST('[]' AS JSON), 'negative', 1 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_neg_009', 120, '帮我订一张明天去北京的机票', 'negative', CAST('[]' AS JSON), CAST('[]' AS JSON), 'negative', 1 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_neg_010', 121, '滴滴打车首单优惠券在哪里领', 'negative', CAST('[]' AS JSON), CAST('[]' AS JSON), 'negative', 1 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_neg_011', 122, '个人所得税专项附加扣除怎么填', 'negative', CAST('[]' AS JSON), CAST('[]' AS JSON), 'negative', 1 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-regression';

-- sunshine-adversarial
UPDATE eval_suite SET kind='standard', format='json', storage='mysql', content_ref=NULL,
  display_name='难例对抗', description='难例对抗评测集', schema_version=1,
  config_json=CAST('{"topK":[3,5,10],"minScore":0.48,"gates":{"recallAt3Min":0.95,"recallAt5Min":0.98,"mrrMin":0.92,"emptyRatePositiveMax":0.0,"emptyRateNegativeMin":0.95,"latencyP95MsMax":500}}' AS JSON), item_count=46
  WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
DELETE FROM eval_suite_item WHERE suite_id=(SELECT id FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial');
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_001', 0, '想请几天假咋整', 'positive', CAST('["leave-policy-v1"]' AS JSON), CAST('["年假", "请假"]' AS JSON), 'adversarial', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_002', 1, '病假条要啥材料', 'positive', CAST('["leave-policy-v1"]' AS JSON), CAST('["病假", "证明"]' AS JSON), 'adversarial', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_003', 2, '婚假能休多久啊', 'positive', CAST('["leave-policy-v1"]' AS JSON), CAST('["婚假"]' AS JSON), 'adversarial', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_004', 3, '陪产假男员工有吗', 'positive', CAST('["leave-policy-v1"]' AS JSON), CAST('["陪产"]' AS JSON), 'adversarial', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_005', 4, '事假扣钱不', 'positive', CAST('["leave-policy-v1"]' AS JSON), CAST('["事假", "无薪"]' AS JSON), 'adversarial', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_006', 5, '迟到五分钟算啥', 'positive', CAST('["attendance-policy-v1"]' AS JSON), CAST('["迟到"]' AS JSON), 'adversarial', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_007', 6, '加班换调休怎么算', 'positive', CAST('["attendance-policy-v1"]' AS JSON), CAST('["调休", "加班"]' AS JSON), 'adversarial', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_008', 7, '弹性上班几点打卡', 'positive', CAST('["attendance-policy-v1"]' AS JSON), CAST('["弹性"]' AS JSON), 'adversarial', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_009', 8, '报一下上个月差旅', 'positive', CAST('["expense-policy-v1"]' AS JSON), CAST('["报销", "差旅"]' AS JSON), 'adversarial', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_010', 9, '餐费发票能报吗', 'positive', CAST('["expense-policy-v1"]' AS JSON), CAST('["餐饮"]' AS JSON), 'adversarial', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_011', 10, '招待费标准多少', 'positive', CAST('["expense-policy-v1"]' AS JSON), CAST('["招待"]' AS JSON), 'adversarial', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_012', 11, '发票抬头开错了', 'positive', CAST('["invoice-faq-v1"]' AS JSON), CAST('["抬头"]' AS JSON), 'adversarial', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_013', 12, '电子票怎么验真', 'positive', CAST('["invoice-faq-v1"]' AS JSON), CAST('["验真"]' AS JSON), 'adversarial', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_014', 13, '专票普票区别报销', 'positive', CAST('["invoice-faq-v1"]' AS JSON), CAST('["专票"]' AS JSON), 'adversarial', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_015', 14, '出差住超标的酒店', 'positive', CAST('["travel-policy-v1"]' AS JSON), CAST('["住宿", "标准"]' AS JSON), 'adversarial', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_016', 15, '机票退票费谁出', 'positive', CAST('["travel-policy-v1"]' AS JSON), CAST('["退票"]' AS JSON), 'adversarial', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_017', 16, '高铁商务座能报吗', 'positive', CAST('["travel-policy-v1"]' AS JSON), CAST('["高铁"]' AS JSON), 'adversarial', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_018', 17, '新人入职要带啥', 'positive', CAST('["onboarding-policy-v1"]' AS JSON), CAST('["入职", "材料"]' AS JSON), 'adversarial', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_019', 18, '试用期能请年假吗', 'positive', CAST('["onboarding-policy-v1", "leave-policy-v1"]' AS JSON), CAST('["试用", "年假"]' AS JSON), 'adversarial', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_020', 19, '转正流程多久', 'positive', CAST('["onboarding-policy-v1"]' AS JSON), CAST('["转正"]' AS JSON), 'adversarial', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_021', 20, '五千块谁批', 'positive', CAST('["finance-approval-v1"]' AS JSON), CAST('["审批", "权限"]' AS JSON), 'adversarial', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_022', 21, '总监审批额度', 'positive', CAST('["finance-approval-v1"]' AS JSON), CAST('["总监"]' AS JSON), 'adversarial', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_023', 22, '部门预算花超了', 'positive', CAST('["budget-policy-v1"]' AS JSON), CAST('["预算", "超支"]' AS JSON), 'adversarial', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_024', 23, '季度预算调剂', 'positive', CAST('["budget-policy-v1"]' AS JSON), CAST('["调剂"]' AS JSON), 'adversarial', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_025', 24, '在家办公算考勤吗', 'positive', CAST('["remote-policy-v1"]' AS JSON), CAST('["远程", "考勤"]' AS JSON), 'adversarial', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_026', 25, '混合办公每周几天', 'positive', CAST('["remote-policy-v1"]' AS JSON), CAST('["混合"]' AS JSON), 'adversarial', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_027', 26, 'VPN连不上咋办', 'positive', CAST('["security-policy-v1"]' AS JSON), CAST('["VPN"]' AS JSON), 'adversarial', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_028', 27, '客户资料能发微信吗', 'positive', CAST('["security-policy-v1"]' AS JSON), CAST('["保密"]' AS JSON), 'adversarial', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_029', 28, '泄密了要担责吗', 'positive', CAST('["security-policy-v1"]' AS JSON), CAST('["泄密"]' AS JSON), 'adversarial', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_030', 29, '报销和请假能一块办吗', 'positive', CAST('["scenario-guide-v1"]' AS JSON), CAST('["报销", "请假"]' AS JSON), 'adversarial', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_031', 30, '出差途中请病假', 'positive', CAST('["scenario-guide-v1", "leave-policy-v1"]' AS JSON), CAST('["出差", "病假"]' AS JSON), 'adversarial', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_032', 31, '远程期间加班怎么算', 'positive', CAST('["scenario-guide-v1", "remote-policy-v1"]' AS JSON), CAST('["远程", "加班"]' AS JSON), 'adversarial', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_033', 32, '离职前未报账', 'positive', CAST('["scenario-guide-v1", "expense-policy-v1"]' AS JSON), CAST('["离职", "报销"]' AS JSON), 'adversarial', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_034', 33, '差旅预算不够订票', 'positive', CAST('["scenario-guide-v1", "travel-policy-v1", "budget-policy-v1"]' AS JSON), CAST('["差旅", "预算"]' AS JSON), 'adversarial', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_035', 34, '年休天数怎么查', 'positive', CAST('["leave-policy-v1"]' AS JSON), CAST('["年假", "工龄"]' AS JSON), 'adversarial', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_036', 35, '产假工资发多少', 'positive', CAST('["leave-policy-v1"]' AS JSON), CAST('["产假"]' AS JSON), 'adversarial', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_037', 36, '加班餐补标准', 'positive', CAST('["attendance-policy-v1", "expense-policy-v1"]' AS JSON), CAST('["加班", "餐补"]' AS JSON), 'adversarial', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_038', 37, '增值税专票丢失', 'positive', CAST('["invoice-faq-v1"]' AS JSON), CAST('["丢失"]' AS JSON), 'adversarial', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_039', 38, '国际出差签证费用', 'positive', CAST('["travel-policy-v1"]' AS JSON), CAST('["签证"]' AS JSON), 'adversarial', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_040', 39, '固定资产采购谁批', 'positive', CAST('["finance-approval-v1", "budget-policy-v1"]' AS JSON), CAST('["固定资产"]' AS JSON), 'adversarial', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_041', 40, 'USB拷贝资料违规吗', 'positive', CAST('["security-policy-v1"]' AS JSON), CAST('["USB"]' AS JSON), 'adversarial', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_042', 41, '入职第一周能远程吗', 'positive', CAST('["onboarding-policy-v1", "remote-policy-v1"]' AS JSON), CAST('["入职", "远程"]' AS JSON), 'adversarial', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_neg_001', 42, '公司股价今天多少', 'negative', CAST('[]' AS JSON), CAST('[]' AS JSON), 'adversarial', 1 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_neg_002', 43, '帮我查快递到哪了', 'negative', CAST('[]' AS JSON), CAST('[]' AS JSON), 'adversarial', 1 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_neg_003', 44, '附近哪家火锅好吃', 'negative', CAST('[]' AS JSON), CAST('[]' AS JSON), 'adversarial', 1 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_adv_neg_004', 45, 'Python怎么学', 'negative', CAST('[]' AS JSON), CAST('[]' AS JSON), 'adversarial', 1 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-adversarial';

-- sunshine-smoke
UPDATE eval_suite SET kind='standard', format='json', storage='mysql', content_ref=NULL,
  display_name='冒烟门禁', description='冒烟门禁评测集（发布/切换配置用）', schema_version=1,
  config_json=CAST('{"topK":[3,5,10],"minScore":0.48,"gates":{"recallAt3Min":0.95,"recallAt5Min":0.98,"mrrMin":0.92,"emptyRatePositiveMax":0.0,"emptyRateNegativeMin":0.95,"latencyP95MsMax":500}}' AS JSON), item_count=50
  WHERE tenant_id='default' AND suite_key='sunshine-smoke';
DELETE FROM eval_suite_item WHERE suite_id=(SELECT id FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke');
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q001', 0, '年假可以请几天', 'positive', CAST('["leave-policy-v1"]' AS JSON), CAST('["年假", "工龄"]' AS JSON), 'leave', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q002', 1, '病假需要什么证明材料', 'positive', CAST('["leave-policy-v1"]' AS JSON), CAST('["病假", "证明", "材料"]' AS JSON), 'leave', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q003', 2, '请假要提前多久申请', 'positive', CAST('["leave-policy-v1"]' AS JSON), CAST('["提前", "申请"]' AS JSON), 'leave', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q004', 3, '事假有没有工资', 'positive', CAST('["leave-policy-v1"]' AS JSON), CAST('["事假", "无薪"]' AS JSON), 'leave', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q005', 4, '婚假有多少天', 'positive', CAST('["leave-policy-v1"]' AS JSON), CAST('["婚假"]' AS JSON), 'leave', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q006', 5, '产假陪产假怎么休', 'positive', CAST('["leave-policy-v1"]' AS JSON), CAST('["产假", "陪产假"]' AS JSON), 'leave', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q007', 6, '直属主管审批职责是什么', 'positive', CAST('["leave-policy-v1"]' AS JSON), CAST('["直属主管", "审批"]' AS JSON), 'process', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q008', 7, 'HR在请假流程中负责什么', 'positive', CAST('["leave-policy-v1"]' AS JSON), CAST('["HR", "假期余额"]' AS JSON), 'process', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q009', 8, '三天以上请假谁审批', 'positive', CAST('["leave-policy-v1"]' AS JSON), CAST('["部门负责人", "3"]' AS JSON), 'process', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q010', 9, '销假是什么意思怎么操作', 'positive', CAST('["leave-policy-v1"]' AS JSON), CAST('["销假"]' AS JSON), 'process', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q011', 10, '加班调休余额在哪里查', 'positive', CAST('["attendance-policy-v1", "leave-policy-v1"]' AS JSON), CAST('["调休", "余额", "OA"]' AS JSON), 'leave', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q012', 11, '丧假可以请几天', 'positive', CAST('["leave-policy-v1"]' AS JSON), CAST('["丧假"]' AS JSON), 'leave', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_neg_001', 12, '公司股票期权怎么兑现', 'negative', CAST('[]' AS JSON), CAST('[]' AS JSON), 'negative', 1 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q_neg_002', 13, '公司股权激励行权如何套现', 'negative', CAST('[]' AS JSON), CAST('[]' AS JSON), 'negative', 1 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q013', 14, '市内交通报销上限多少', 'positive', CAST('["expense-policy-v1"]' AS JSON), CAST('["市内交通", "200"]' AS JSON), 'expense', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q014', 15, '差旅住宿一晚能报多少钱', 'positive', CAST('["expense-policy-v1"]' AS JSON), CAST('["差旅", "住宿", "600"]' AS JSON), 'expense', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q015', 16, '餐饮招待费人均标准', 'positive', CAST('["expense-policy-v1"]' AS JSON), CAST('["餐饮", "150"]' AS JSON), 'expense', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q016', 17, '报销需要什么样的发票', 'positive', CAST('["expense-policy-v1"]' AS JSON), CAST('["发票", "增值税"]' AS JSON), 'expense', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q017', 18, '超过五千的报销谁审批', 'positive', CAST('["expense-policy-v1"]' AS JSON), CAST('["5000", "CFO"]' AS JSON), 'expense', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q018', 19, '拆单报销是否允许', 'positive', CAST('["expense-policy-v1"]' AS JSON), CAST('["拆单", "禁止"]' AS JSON), 'expense', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q019', 20, '私人消费能报销吗', 'positive', CAST('["expense-policy-v1"]' AS JSON), CAST('["私人", "禁止"]' AS JSON), 'expense', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q020', 21, '报销审批流程有几步', 'positive', CAST('["expense-policy-v1"]' AS JSON), CAST('["审批", "财务复核"]' AS JSON), 'expense', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q021', 22, '电子发票报销要注意什么', 'positive', CAST('["expense-policy-v1", "invoice-faq-v1"]' AS JSON), CAST('["电子发票", "税号"]' AS JSON), 'expense', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q022', 23, '办公用品采购怎么报销', 'positive', CAST('["expense-policy-v1"]' AS JSON), CAST('["办公用品"]' AS JSON), 'expense', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q023', 24, '虚假发票报销后果', 'positive', CAST('["expense-policy-v1"]' AS JSON), CAST('["虚假发票"]' AS JSON), 'expense', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q024', 25, '因公交通费谁审批', 'positive', CAST('["expense-policy-v1"]' AS JSON), CAST('["直属主管"]' AS JSON), 'expense', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q025', 26, '迟到几次算旷工', 'positive', CAST('["attendance-policy-v1"]' AS JSON), CAST('["迟到", "旷工"]' AS JSON), 'attendance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q026', 27, '加班需要提前申请吗', 'positive', CAST('["attendance-policy-v1"]' AS JSON), CAST('["加班", "申请"]' AS JSON), 'attendance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q027', 28, '周末加班怎么算调休', 'positive', CAST('["attendance-policy-v1"]' AS JSON), CAST('["周末", "调休"]' AS JSON), 'attendance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q028', 29, '弹性上下班时间规定', 'positive', CAST('["attendance-policy-v1"]' AS JSON), CAST('["弹性"]' AS JSON), 'attendance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q029', 30, '忘打卡怎么补签', 'positive', CAST('["attendance-policy-v1"]' AS JSON), CAST('["补签", "打卡"]' AS JSON), 'attendance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q030', 31, '外出办公如何登记考勤', 'positive', CAST('["attendance-policy-v1"]' AS JSON), CAST('["外出"]' AS JSON), 'attendance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q031', 32, '夜班补贴标准', 'positive', CAST('["attendance-policy-v1"]' AS JSON), CAST('["夜班"]' AS JSON), 'attendance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q032', 33, '法定节假日加班工资', 'positive', CAST('["attendance-policy-v1"]' AS JSON), CAST('["法定节假日"]' AS JSON), 'attendance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q033', 34, '考勤异常申诉找谁', 'positive', CAST('["attendance-policy-v1"]' AS JSON), CAST('["申诉", "HR"]' AS JSON), 'attendance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q034', 35, '远程办公考勤要求', 'positive', CAST('["attendance-policy-v1"]' AS JSON), CAST('["远程"]' AS JSON), 'attendance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q035', 36, '新员工入职第一天做什么', 'positive', CAST('["onboarding-policy-v1"]' AS JSON), CAST('["入职", "第一天"]' AS JSON), 'onboarding', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q036', 37, '入职需要带哪些材料', 'positive', CAST('["onboarding-policy-v1"]' AS JSON), CAST('["材料", "身份证"]' AS JSON), 'onboarding', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q037', 38, '试用期多长时间', 'positive', CAST('["onboarding-policy-v1"]' AS JSON), CAST('["试用期"]' AS JSON), 'onboarding', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q038', 39, '入职培训有哪些环节', 'positive', CAST('["onboarding-policy-v1"]' AS JSON), CAST('["培训"]' AS JSON), 'onboarding', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q039', 40, '工牌和账号什么时候开通', 'positive', CAST('["onboarding-policy-v1"]' AS JSON), CAST('["工牌", "账号"]' AS JSON), 'onboarding', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q040', 41, '入职导师制度是什么', 'positive', CAST('["onboarding-policy-v1"]' AS JSON), CAST('["导师"]' AS JSON), 'onboarding', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q041', 42, '劳动合同什么时候签', 'positive', CAST('["onboarding-policy-v1"]' AS JSON), CAST('["劳动合同"]' AS JSON), 'onboarding', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q042', 43, '入职体检要求', 'positive', CAST('["onboarding-policy-v1"]' AS JSON), CAST('["体检"]' AS JSON), 'onboarding', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q043', 44, '部门经理审批额度上限', 'positive', CAST('["finance-approval-v1"]' AS JSON), CAST('["部门经理", "额度"]' AS JSON), 'finance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q044', 45, 'CFO审批什么金额以上的单据', 'positive', CAST('["finance-approval-v1"]' AS JSON), CAST('["CFO"]' AS JSON), 'finance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q045', 46, '财务复核的职责是什么', 'positive', CAST('["finance-approval-v1"]' AS JSON), CAST('["财务复核"]' AS JSON), 'finance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q046', 47, '预算外支出谁批准', 'positive', CAST('["finance-approval-v1", "budget-policy-v1"]' AS JSON), CAST('["预算外"]' AS JSON), 'finance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q047', 48, '采购付款审批矩阵', 'positive', CAST('["finance-approval-v1"]' AS JSON), CAST('["采购", "付款"]' AS JSON), 'finance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';
INSERT INTO eval_suite_item (suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, relevant_keywords, category, expect_empty) SELECT id, 'q048', 49, '差旅费审批权限分级', 'positive', CAST('["finance-approval-v1", "expense-policy-v1"]' AS JSON), CAST('["差旅", "审批"]' AS JSON), 'finance', 0 FROM eval_suite WHERE tenant_id='default' AND suite_key='sunshine-smoke';

-- 内置知识库文档元数据（SSOT：doc_id + display_name；正文经 ingest 写入 Milvus/ES）
USE sunshine_rag;

INSERT INTO document (tenant_id, kb_id, doc_id, display_name, source_type)
VALUES
    ('default', 'default', 'leave-policy-v1', '公司请假流程规范', 'markdown'),
    ('default', 'default', 'expense-policy-v1', '公司报销管理制度', 'markdown'),
    ('default', 'default', 'attendance-policy-v1', '考勤与加班管理规定', 'markdown'),
    ('default', 'default', 'onboarding-policy-v1', '新员工入职指引', 'markdown'),
    ('default', 'default', 'finance-approval-v1', '财务审批权限矩阵', 'markdown'),
    ('default', 'default', 'invoice-faq-v1', '发票与税务合规FAQ', 'markdown'),
    ('default', 'default', 'budget-policy-v1', '部门预算管理办法', 'markdown'),
    ('default', 'default', 'remote-policy-v1', '远程办公与混合办公管理办法', 'markdown'),
    ('default', 'default', 'security-policy-v1', '信息安全与数据保密制度', 'markdown'),
    ('default', 'default', 'travel-policy-v1', '差旅费管理办法', 'markdown'),
    ('default', 'default', 'scenario-guide-v1', '员工场景速查与多制度交叉指引', 'markdown')
ON DUPLICATE KEY UPDATE
    display_name = VALUES(display_name),
    source_type = VALUES(source_type);

-- default 知识库 RAG 配置 bundle（业务参数 SSOT；仅 seed 生效版，草稿由用户「复制为草稿」创建）
USE sunshine_rag;

INSERT INTO rag_config_bundle (tenant_id, kb_id, created_at, updated_at)
VALUES ('default', 'default', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

SET @bundle_id = LAST_INSERT_ID();

INSERT INTO rag_config_version (bundle_id, version_no, status, payload_json, change_note, published_at)
VALUES (@bundle_id, 1, 'active', CAST('{"search": {"minScore": 0.48, "strategy": "hybrid+rerank", "rrfK": 60, "hybridPoolSize": 20, "defaultTopK": 3}, "rerank": {"enabled": true, "minScore": 0.25, "minRelevance": 0.25}, "chunk": {"maxSize": 1200}, "rewrite": {"rag": {"enabled": true, "model": "deepseek-v4-flash", "systemPrompt": "你是企业知识库检索 query 优化助手。用户问题将用于向量/混合检索。\\n请补全域内关键词（制度、流程、报销、请假、差旅、考勤等），标准化专有名词表述。\\n保留原意，不要编造事实；若已足够清晰则轻微润色即可。\\n只输出 JSON：{\\"query\\":\\"优化后的检索 query\\"}，不要 markdown 或其他文字。"}, "hyde": {"enabled": true, "model": "deepseek-v4-flash", "maxChars": 480, "systemPrompt": "你是企业知识库 HyDE 助手。根据用户问题，写一段**可能出现在企业制度/流程文档中**的中文段落，\\n用于向量检索匹配；不要写问答体，不要写「根据…规定」等元叙述，直接写制度条文式正文。\\n只引用常见域内概念（报销、差旅、请假、考勤、审批等），**禁止编造**具体金额/日期/人名。\\n只输出 JSON：{\\"document\\":\\"假想文档段落\\"}，不要 markdown 或其他文字。"}, "emptyRecall": {"enabled": true, "model": "deepseek-v4-flash", "maxAlternatives": 2, "systemPrompt": "你是企业知识库检索 query 改写助手。用户原始问题在向量/混合检索中零命中。\\n请生成 %d 个不同表述的中文检索 query，用于二次检索。\\n要求：\\n- 必须保留原问题的核心业务领域与关键名词，禁止改问无关主题；\\n- 仅在同领域内补充「制度」「管理办法」「流程规范」等同义表述；\\n- 不要编造事实。\\n只输出 JSON：{\\"queries\\":[\\"改写1\\",\\"改写2\\"]}，不要 markdown 或其他文字。"}}}' AS JSON), 'docker-init', CURRENT_TIMESTAMP);

UPDATE rag_config_bundle
SET active_published_version_id = LAST_INSERT_ID(),
    draft_version_id = NULL
WHERE id = @bundle_id;

-- 文档版本 MinIO 路径 + 文档生效版本指针
USE sunshine_rag;

ALTER TABLE document
    ADD COLUMN active_version INT NULL AFTER source_type;

ALTER TABLE document_version
    ADD COLUMN storage_path VARCHAR(512) NULL AFTER parsed_markdown;

-- 文档版本号改为时间戳 yyyyMMddHHmmss
USE sunshine_rag;

ALTER TABLE document
    MODIFY COLUMN active_version VARCHAR(14) NULL;

ALTER TABLE document_version
    MODIFY COLUMN version VARCHAR(14) NOT NULL;

-- ingest_job 异步解析进度字段
USE sunshine_rag;

ALTER TABLE ingest_job
    ADD COLUMN target_version VARCHAR(14) NULL AFTER doc_id,
    ADD COLUMN source_type VARCHAR(16) NULL AFTER mime_type,
    ADD COLUMN progress_pct DOUBLE NULL AFTER status,
    ADD COLUMN progress_page INT NULL AFTER progress_pct,
    ADD COLUMN total_pages INT NULL AFTER progress_page,
    ADD COLUMN source_object_key VARCHAR(512) NULL AFTER total_pages;

USE sunshine_rag;
ALTER TABLE document_version
    ADD COLUMN chunk_strategy VARCHAR(32) NULL AFTER chunk_count,
    ADD COLUMN chunk_params_json JSON NULL AFTER chunk_strategy;
