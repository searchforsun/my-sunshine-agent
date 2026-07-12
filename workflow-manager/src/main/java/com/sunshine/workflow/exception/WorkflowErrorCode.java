package com.sunshine.workflow.exception;

import com.sunshine.common.core.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum WorkflowErrorCode implements ErrorCode {
    WORKFLOW_NOT_FOUND(404, "workflow_not_found", "工作流不存在"),
    WORKFLOW_EXISTS(409, "workflow_exists", "工作流 ID 已存在"),
    VERSION_NOT_FOUND(404, "workflow_version_not_found", "工作流版本不存在"),
    PLAN_INVALID(422, "workflow_plan_invalid", "Plan 校验失败"),
    DRAFT_MISSING(400, "workflow_draft_missing", "草稿不存在"),
    DRAFT_ALREADY_EXISTS(409, "workflow_draft_already_exists", "已有草稿版本，请先发布或删除后再操作"),
    DESCRIPTION_REQUIRED(400, "workflow_description_required", "描述不能为空（用于路由命中）"),
    ENABLE_REQUIRES_PUBLISHED(400, "workflow_enable_requires_published", "须先发布生效版本才能启用"),
    LAST_VERSION_DELETE_FORBIDDEN(400, "workflow_last_version_delete_forbidden", "至少保留一个版本");

    private final int code;
    private final String key;
    private final String message;
}
