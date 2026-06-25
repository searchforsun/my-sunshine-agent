ALTER TABLE chat_conversation
    ADD COLUMN execution_preference VARCHAR(32) NULL COMMENT 'auto|simple-llm|react|workflow|plan-workflow';
