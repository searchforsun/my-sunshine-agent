ALTER TABLE chat_conversation
    ADD COLUMN kb_id VARCHAR(64) NULL COMMENT '会话绑定的知识库 id';
