-- Sunshine 业务库：sunshine_skill
USE sunshine_skill;

-- V1__skill_schema.sql
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

-- V2__seed_skills.sql
INSERT INTO skill_definition (id, display_name, description, enabled, active_version) VALUES
('finance-analysis', '财务合规分析', '待审批单据与制度/规则的内部分析子 Agent', 1, 1),
('policy-review', '制度审查', '企业制度条款检索与解读', 1, 1),
('compliance-check', '合规对比', '制度与业务数据合规对比', 1, 1);

INSERT INTO skill_version (skill_id, version, system_overlay, tools_json, max_iters, status) VALUES
('finance-analysis', 1,
 '你是财务合规分析子 Agent（workflow 内嵌节点，不面向用户）。\n仅基于上游注入的待办/制度材料做内部分析；结论供下游 llm 节点润色后展示。\n禁止直接向用户致辞；禁止编造未出现在注入材料中的单据或金额。',
 '["list_finance_messages"]', 4, 'published'),
('policy-review', 1,
 '你是企业制度审查子 Agent。仅根据注入的检索结果或制度片段做内部分析，不面向用户直接答复。',
 '["search_knowledge"]', 4, 'published'),
('compliance-check', 1,
 '你是合规对比子 Agent。对比制度片段与业务数据（待办/单据），输出内部分析结论，不面向用户。',
 '["search_knowledge","list_finance_messages"]', 4, 'published');

-- V3__skill_standard_fields.sql
ALTER TABLE skill_version
    ADD COLUMN side_effect VARCHAR(32) NOT NULL DEFAULT 'read' AFTER max_iters,
    ADD COLUMN sandbox VARCHAR(32) NOT NULL DEFAULT 'none' AFTER side_effect,
    ADD COLUMN references_json VARCHAR(1024) NOT NULL DEFAULT '[]' AFTER sandbox,
    ADD COLUMN scripts_json VARCHAR(1024) NOT NULL DEFAULT '[]' AFTER references_json;

-- V4__skill_version_maintainer.sql
ALTER TABLE skill_version
    ADD COLUMN maintainer VARCHAR(64) NULL AFTER status;

-- V5__remove_seed_skills.sql
-- 移除历史 Flyway/启动种子 Skill；示例文档 SSOT：docs/skills/（不自动入库）
DELETE FROM skill_version WHERE skill_id IN (
    'finance-analysis', 'policy-review', 'compliance-check', 'finance-report', 'knowledge-brief'
);
DELETE FROM skill_definition WHERE id IN (
    'finance-analysis', 'policy-review', 'compliance-check', 'finance-report', 'knowledge-brief'
);

-- Flyway 基线（与 classpath db/migration 校验一致，避免服务启动重复建表）
USE sunshine_skill;
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
INSERT INTO flyway_schema_history VALUES (1, '1', 'skill schema', 'SQL', 'V1__skill_schema.sql', 659903771, 'docker-init', NOW(), 0, 1);
INSERT INTO flyway_schema_history VALUES (2, '2', 'seed skills', 'SQL', 'V2__seed_skills.sql', 2090618454, 'docker-init', NOW(), 0, 1);
INSERT INTO flyway_schema_history VALUES (3, '3', 'skill standard fields', 'SQL', 'V3__skill_standard_fields.sql', 1107919894, 'docker-init', NOW(), 0, 1);
INSERT INTO flyway_schema_history VALUES (4, '4', 'skill version maintainer', 'SQL', 'V4__skill_version_maintainer.sql', -1672759055, 'docker-init', NOW(), 0, 1);
INSERT INTO flyway_schema_history VALUES (5, '5', 'remove seed skills', 'SQL', 'V5__remove_seed_skills.sql', 1789836275, 'docker-init', NOW(), 0, 1);
