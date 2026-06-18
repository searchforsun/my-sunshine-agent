-- Sunshine 会话与记忆清库（配合 scripts/clear_session_cache.py）
-- 默认库：sunshine_chat（与 docs/nacos/sunshine-orchestrator.yaml 一致）

USE sunshine_chat;

SET FOREIGN_KEY_CHECKS = 0;

TRUNCATE TABLE chat_message;
TRUNCATE TABLE chat_conversation;
TRUNCATE TABLE conversation_memory_mtm;

-- 可选：审计日志（脚本 -IncludeAudit 时执行）
-- TRUNCATE TABLE chat_audit_log;

-- 可选：长期用户画像（脚本 -IncludeLtm 时执行）
-- TRUNCATE TABLE user_memory_profile;

SET FOREIGN_KEY_CHECKS = 1;
