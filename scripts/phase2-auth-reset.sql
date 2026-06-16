-- REQ-PHASE2-AUTH 上线前清库（决策 C）
-- 执行前请确认已备份；会清空所有会话与消息

USE sunshine_chat;

TRUNCATE TABLE chat_message;
TRUNCATE TABLE chat_conversation;

-- 前端请清除 localStorage:
-- sunshine-user-id, sunshine-token, sunshine-current-conversation-id, sunshine-active-generation
