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
    VERSION_NOT_FOUND(404, "rag_version_not_found", "文档版本不存在");

    private final int code;
    private final String key;
    private final String message;
}
