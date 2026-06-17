CREATE TABLE chat_audit_log (
    id              VARCHAR(64)  NOT NULL PRIMARY KEY,
    conversation_id VARCHAR(64)  NOT NULL,
    message_id      VARCHAR(64)  NOT NULL,
    user_id         VARCHAR(64)  NOT NULL,
    tenant_id       VARCHAR(64)  NOT NULL DEFAULT 'default',
    event_type      VARCHAR(32)  NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    intent          VARCHAR(32)  NULL,
    content_len     INT          NOT NULL DEFAULT 0,
    payload         JSON         NULL,
    created_at      DATETIME(3)  NOT NULL,
    INDEX idx_audit_user_time (user_id, created_at),
    INDEX idx_audit_msg (message_id)
);
