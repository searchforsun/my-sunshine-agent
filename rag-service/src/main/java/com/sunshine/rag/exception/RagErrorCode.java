package com.sunshine.rag.exception;

import com.sunshine.common.core.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** rag-service 业务错误码 */
@Getter
@RequiredArgsConstructor
public enum RagErrorCode implements ErrorCode {

    CONTENT_EMPTY(400, "rag_content_empty", "文档内容不能为空"),
    QUERY_EMPTY(400, "rag_query_empty", "查询内容不能为空"),
    ADMIN_TOKEN_INVALID(403, "rag_admin_token_invalid", "无效 admin token"),
    KB_NOT_FOUND(404, "rag_kb_not_found", "知识库不存在"),
    KB_ALREADY_EXISTS(409, "rag_kb_already_exists", "知识库 ID 已存在"),
    DOC_NOT_FOUND(404, "rag_doc_not_found", "文档不存在"),
    DOC_ALREADY_EXISTS(409, "rag_doc_already_exists", "文档 ID 已存在"),
    DOC_ID_DISPLAY_NAME_REQUIRED(400, "rag_doc_id_display_name_required", "文档 ID 与显示名称不能为空"),
    DRAFT_ALREADY_EXISTS(409, "rag_draft_already_exists", "已有内容草稿，请先发布或删除草稿"),
    FILE_TYPE_NOT_SUPPORTED(400, "rag_file_type_not_supported", "文件类型与文档类型不匹配或尚未支持"),
    DOC_SOURCE_TYPE_INVALID(400, "rag_doc_source_type_invalid", "不支持的文档类型"),
    VERSION_NOT_EDITABLE(400, "rag_version_not_editable", "仅草稿版本可编辑"),
    VERSION_NO_CONTENT(400, "rag_version_no_content", "版本尚无内容，请先上传或编写"),
    SOURCE_CONTENT_MISSING(404, "rag_source_content_missing", "版本原文不存在"),
    VERSION_NOT_FOUND(404, "rag_version_not_found", "文档版本不存在"),
    CONFIG_BUNDLE_NOT_FOUND(404, "rag_config_bundle_not_found", "知识库配置 bundle 不存在，请先执行初始化 SQL"),
    CONFIG_PAYLOAD_INVALID(400, "rag_config_payload_invalid", "配置 payload 不完整或结构无效"),
    INGEST_JOB_NOT_FOUND(404, "rag_ingest_job_not_found", "入库任务不存在"),
    INGEST_INVALID_STATUS(400, "rag_ingest_invalid_status", "入库任务状态无效或不允许此操作"),
    INGEST_PARSE_FAILED(400, "rag_ingest_parse_failed", "文件解析失败"),
    INGEST_DESENSITIZE_FAILED(502, "rag_ingest_desensitize_failed", "入库前脱敏失败"),
    OCR_NOT_CONFIGURED(503, "rag_ocr_not_configured", "OCR 未启用或未配置 API Key"),
    DOCUMENT_PARSE_IN_PROGRESS(409, "rag_document_parse_in_progress", "文档仍在解析中，请稍候"),
    INGEST_QUARANTINE_PENDING(400, "rag_ingest_quarantine_pending", "解析置信度偏低，请先确认解析内容后再发布"),
    UNKNOWN_CHUNK_STRATEGY(400, "rag_unknown_chunk_strategy", "未知的分块策略"),
    CHUNK_LIMIT_EXCEEDED(400, "rag_chunk_limit_exceeded", "分块数量超过上限 2000，请增大块大小或拆分文档"),
    PREVIEW_NOT_FOUND(400, "rag_preview_not_found", "分块预览不存在或已失效，请重新预览"),
    PREVIEW_EXPIRED(400, "rag_preview_expired", "分块预览已过期，请重新预览"),
    PREVIEW_MISMATCH(400, "rag_preview_mismatch", "分块预览与当前文档不匹配，请重新预览");

    private final int code;
    private final String key;
    private final String message;
}
