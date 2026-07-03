-- 文档版本号改为时间戳 yyyyMMddHHmmss
USE sunshine_rag;

ALTER TABLE document
    MODIFY COLUMN active_version VARCHAR(14) NULL;

ALTER TABLE document_version
    MODIFY COLUMN version VARCHAR(14) NOT NULL;
