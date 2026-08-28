-- 一次性：旧 MTM/LTM → L1/L2（开发期不兼容迁移，禁止 Flyway）
-- 用法: mysql -h ecs4c16g -uroot -proot123 < scripts/migrate_context_l1_l2.sql
-- 或: python -c "from sunshine_lib import run_mysql; ..." 读本文件执行

USE sunshine_chat;

DROP TABLE IF EXISTS conversation_memory_mtm, user_memory_profile;

CREATE TABLE IF NOT EXISTS conversation_context_l1 (
    conv_id              VARCHAR(32)  NOT NULL PRIMARY KEY,
    user_id              VARCHAR(64)  NOT NULL,
    tenant_id            VARCHAR(32)  NOT NULL DEFAULT 'default',
    mid_answers          MEDIUMTEXT   NULL COMMENT 'JSON map msgId -> answer summary',
    far_summary          MEDIUMTEXT   NULL,
    far_folded_msg_ids   MEDIUMTEXT   NULL COMMENT 'JSON array of msgIds 已退役（压缩点之前；Near 起点=该边界之后）',
    far_summarized_msg_ids MEDIUMTEXT NULL COMMENT 'JSON array of msgIds 已实际折叠进 far_summary（far_folded 子集；NULL=存量行，视为与 far_folded 一致）',
    near_n               INT          NOT NULL DEFAULT 8,
    mid_n                INT          NOT NULL DEFAULT 8,
    updated_at           TIMESTAMP(3) NOT NULL,
    INDEX idx_l1_user (user_id, tenant_id)
);

-- 已有库缺列时手工执行（CREATE IF NOT EXISTS 不会补列）:
-- ALTER TABLE conversation_context_l1
--   ADD COLUMN far_folded_msg_ids MEDIUMTEXT NULL
--   COMMENT 'JSON array of msgIds 已退役（压缩点之前；Near 起点=该边界之后）' AFTER far_summary;
-- ALTER TABLE conversation_context_l1
--   ADD COLUMN far_summarized_msg_ids MEDIUMTEXT NULL
--   COMMENT 'JSON array of msgIds 已实际折叠进 far_summary（far_folded 子集；NULL=存量行，视为与 far_folded 一致）'
--   AFTER far_folded_msg_ids;

CREATE TABLE IF NOT EXISTS user_context_state (
    id              VARCHAR(32)  NOT NULL PRIMARY KEY,
    user_id         VARCHAR(64)  NOT NULL,
    tenant_id       VARCHAR(32)  NOT NULL DEFAULT 'default',
    kind            VARCHAR(32)  NOT NULL,
    state_key       VARCHAR(128) NOT NULL,
    state_value     TEXT         NOT NULL,
    confidence      DOUBLE       NOT NULL DEFAULT 0,
    status          VARCHAR(16)  NOT NULL DEFAULT 'active',
    expires_at      TIMESTAMP(3) NULL,
    source_msg_id   VARCHAR(64)  NULL,
    created_at      TIMESTAMP(3) NOT NULL,
    updated_at      TIMESTAMP(3) NOT NULL,
    -- 无 UNIQUE(kind,key)：同 key 可多条 superseded 审计；应用保证至多一条 active
    INDEX idx_ctx_user_kind_key_status (user_id, tenant_id, kind, state_key, status),
    INDEX idx_ctx_user_status (user_id, tenant_id, status),
    INDEX idx_ctx_expires (expires_at)
);

-- 已有库若仍为旧 UNIQUE(uk_ctx_user_kind_key)，手工迁移：
-- ALTER TABLE user_context_state DROP INDEX uk_ctx_user_kind_key;
-- ALTER TABLE user_context_state
--   ADD INDEX idx_ctx_user_kind_key_status (user_id, tenant_id, kind, state_key, status);

-- 业务权威层 §4.3：偏好场景作用域 + 确认态（已有库手工执行）:
-- ALTER TABLE user_context_state
--   ADD COLUMN biz_scene_scope VARCHAR(64) NULL DEFAULT '*'
--     COMMENT '场景偏好作用域（业务权威层 §4.3）：*=全局 | 具体 biz_scene' AFTER background,
--   ADD COLUMN confirm_status VARCHAR(16) NULL DEFAULT 'confirmed'
--     COMMENT '偏好确认态（业务权威层 §4.3）：confirmed 默认可装载 | inferred 默认不装载'
--     AFTER biz_scene_scope;

-- 业务任务板权威态（业务权威层 §4.1）：跨会话流程状态；与 agent 执行态边界隔离
CREATE TABLE IF NOT EXISTS business_task (
    task_id                      VARCHAR(32)  NOT NULL PRIMARY KEY,
    tenant_id                    VARCHAR(32)  NOT NULL DEFAULT 'default',
    user_id                      VARCHAR(64)  NOT NULL,
    biz_scene                    VARCHAR(64)  NOT NULL,
    status                       VARCHAR(24)  NOT NULL DEFAULT 'pending'
        COMMENT 'pending|running|awaiting_confirm|done|archived|failed',
    title                        VARCHAR(128) NOT NULL,
    steps_json                   TEXT         NULL COMMENT '当前步骤骨架（结构化，非散文全文）',
    pending_confirmations_json   TEXT         NULL COMMENT '待确认项',
    retry_count                  INT          NOT NULL DEFAULT 0,
    deadline                     TIMESTAMP(3) NULL,
    risk_level                   VARCHAR(16)  NULL DEFAULT 'low' COMMENT 'low|medium|high',
    external_ticket_ref          VARCHAR(128) NULL COMMENT '外系统工单/审批单号指针（不含原文）',
    evidence_refs_json           TEXT         NULL COMMENT '证据指针列表（OSS key / messageId / ticket）',
    conversation_id              VARCHAR(64)  NULL COMMENT '最近关联会话',
    created_at                   TIMESTAMP(3) NOT NULL,
    updated_at                   TIMESTAMP(3) NOT NULL,
    INDEX idx_biz_task_user_status (tenant_id, user_id, status, updated_at),
    INDEX idx_biz_task_scene (tenant_id, user_id, biz_scene, status, updated_at),
    INDEX idx_biz_task_ticket (tenant_id, external_ticket_ref)
);
