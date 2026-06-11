CREATE TABLE chat_conversation (
    id          VARCHAR(64)  NOT NULL PRIMARY KEY,
    user_id     VARCHAR(64)  NOT NULL,
    tenant_id   VARCHAR(64)  NOT NULL DEFAULT 'default',
    title       VARCHAR(128) NOT NULL DEFAULT '新对话',
    created_at  DATETIME(3)  NOT NULL,
    updated_at  DATETIME(3)  NOT NULL,
    INDEX idx_user_tenant_updated (user_id, tenant_id, updated_at)
);

CREATE TABLE chat_message (
    id               VARCHAR(64)  NOT NULL PRIMARY KEY,
    conversation_id  VARCHAR(64)  NOT NULL,
    seq              INT          NOT NULL,
    role             VARCHAR(16)  NOT NULL,
    content          MEDIUMTEXT   NOT NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'completed',
    intent           VARCHAR(32)  NULL,
    resume_count     INT          NOT NULL DEFAULT 0,
    created_at       DATETIME(3)  NOT NULL,
    updated_at       DATETIME(3)  NOT NULL,
    UNIQUE KEY uk_conv_seq (conversation_id, seq),
    INDEX idx_conv_created (conversation_id, created_at),
    CONSTRAINT fk_msg_conv FOREIGN KEY (conversation_id) REFERENCES chat_conversation(id) ON DELETE CASCADE
);
