CREATE TABLE react_task_board (
    id              VARCHAR(64)  NOT NULL PRIMARY KEY,
    message_id      VARCHAR(64)  NOT NULL,
    conversation_id VARCHAR(64)  NOT NULL,
    tenant_id       VARCHAR(64)  NOT NULL DEFAULT 'default',
    user_id         VARCHAR(64)  NOT NULL,
    revision        INT          NOT NULL,
    items_json      JSON         NOT NULL,
    created_at      DATETIME(3)  NOT NULL,
    updated_at      DATETIME(3)  NOT NULL,
    UNIQUE KEY uk_react_task_board_msg (message_id),
    INDEX idx_react_task_board_conv (conversation_id, updated_at)
);
