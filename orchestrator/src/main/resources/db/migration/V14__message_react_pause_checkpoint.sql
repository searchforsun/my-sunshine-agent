ALTER TABLE chat_message
    ADD COLUMN react_pause_checkpoint MEDIUMTEXT NULL COMMENT 'ReAct 暂停续跑 JSON';
