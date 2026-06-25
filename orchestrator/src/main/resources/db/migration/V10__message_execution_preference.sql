ALTER TABLE chat_message
    ADD COLUMN execution_preference VARCHAR(32) NULL COMMENT 'user 消息发送时 executionPreference';
