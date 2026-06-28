ALTER TABLE chat_message
    ADD COLUMN content_blocks MEDIUMTEXT NULL COMMENT 'ReAct 正文分段 JSON' AFTER steps;
