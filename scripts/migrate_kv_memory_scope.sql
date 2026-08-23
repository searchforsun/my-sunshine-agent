-- 一次性：KV Memory scope 化（user_context_state 加 scope/workspace_id/background）
-- 用法: mysql -h <host> -uroot -proot123 < scripts/migrate_kv_memory_scope.sql
-- 注意: 一次性脚本，重复执行会因列/索引已存在而报错；如需对已跑过的库再迁移，用下方"手工迁移"注释核对缺失项后单独执行。
USE sunshine_chat;

ALTER TABLE user_context_state
  ADD COLUMN scope         VARCHAR(16) NOT NULL DEFAULT 'user' AFTER id,
  ADD COLUMN workspace_id  VARCHAR(64) NULL AFTER user_id,
  ADD COLUMN background    VARCHAR(256) NULL AFTER state_value;

-- 存量行均为 user scope（默认值生效），无需回填

CREATE INDEX idx_ctx_ws_kind_key_status (workspace_id, tenant_id, kind, state_key, status) ON user_context_state;

-- 回滚：
-- ALTER TABLE user_context_state
--   DROP INDEX idx_ctx_ws_kind_key_status,
--   DROP COLUMN background,
--   DROP COLUMN workspace_id,
--   DROP COLUMN scope;

-- 手工迁移（库中已存在部分列/索引时，对缺失项单独执行）：
-- ALTER TABLE user_context_state ADD COLUMN scope VARCHAR(16) NOT NULL DEFAULT 'user' AFTER id;
-- ALTER TABLE user_context_state ADD COLUMN workspace_id VARCHAR(64) NULL AFTER user_id;
-- ALTER TABLE user_context_state ADD COLUMN background VARCHAR(256) NULL AFTER state_value;
-- CREATE INDEX idx_ctx_ws_kind_key_status (workspace_id, tenant_id, kind, state_key, status) ON user_context_state;
