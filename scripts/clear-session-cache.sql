-- Sunshine 会话与三层上下文清库（配合 scripts/clear_session_cache.py）
-- 默认库：sunshine_chat（与 docs/nacos/sunshine-orchestrator.yaml 一致）
-- L3 向量在脚本中清 Milvus collection sunshine_chat_history（本 SQL 不含）

USE sunshine_chat;

SET FOREIGN_KEY_CHECKS = 0;

TRUNCATE TABLE chat_message;
TRUNCATE TABLE chat_conversation;
TRUNCATE TABLE conversation_context_l1;   -- L1
TRUNCATE TABLE user_context_state;       -- L2
TRUNCATE TABLE task_board;               -- 任务板终态快照（M0 任务清单记忆）
TRUNCATE TABLE business_task;            -- 业务任务板权威态（业务上下文权威层）

-- 可选：审计日志（脚本 --include-audit 时执行）
-- TRUNCATE TABLE chat_audit_log;

SET FOREIGN_KEY_CHECKS = 1;
