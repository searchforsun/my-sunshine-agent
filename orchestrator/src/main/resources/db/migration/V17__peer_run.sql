CREATE TABLE peer_run (
    id              VARCHAR(64)  NOT NULL PRIMARY KEY,
    message_id      VARCHAR(64)  NOT NULL,
    conversation_id VARCHAR(64)  NOT NULL,
    tenant_id       VARCHAR(64)  NOT NULL DEFAULT 'default',
    user_id         VARCHAR(64)  NOT NULL,
    template_id     VARCHAR(128) NOT NULL,
    transcript_json JSON         NOT NULL,
    created_at      DATETIME(3)  NOT NULL,
    updated_at      DATETIME(3)  NOT NULL,
    UNIQUE KEY uk_peer_run_msg (message_id),
    INDEX idx_peer_run_conv (conversation_id, updated_at)
);
