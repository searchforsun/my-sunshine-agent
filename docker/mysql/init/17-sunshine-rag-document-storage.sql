-- 文档版本 MinIO 路径 + 文档生效版本指针
USE sunshine_rag;

ALTER TABLE document
    ADD COLUMN active_version INT NULL AFTER source_type;

ALTER TABLE document_version
    ADD COLUMN storage_path VARCHAR(512) NULL AFTER parsed_markdown;
