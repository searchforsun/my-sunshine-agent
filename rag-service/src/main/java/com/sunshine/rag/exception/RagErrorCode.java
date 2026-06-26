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
    ADMIN_TOKEN_INVALID(403, "rag_admin_token_invalid", "无效 admin token");

    private final int code;
    private final String key;
    private final String message;
}
