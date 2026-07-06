-- ingest_job 异步解析进度字段
USE sunshine_rag;

ALTER TABLE ingest_job
    ADD COLUMN target_version VARCHAR(14) NULL AFTER doc_id,
    ADD COLUMN source_type VARCHAR(16) NULL AFTER mime_type,
    ADD COLUMN progress_pct DOUBLE NULL AFTER status,
    ADD COLUMN progress_page INT NULL AFTER progress_pct,
    ADD COLUMN total_pages INT NULL AFTER progress_page,
    ADD COLUMN source_object_key VARCHAR(512) NULL AFTER total_pages;
