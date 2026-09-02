package com.sunshine.prompt.exception;

import com.sunshine.common.core.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PromptErrorCode implements ErrorCode {
    ID_KIND_DISPLAY_NAME_REQUIRED(400, "prompt_id_kind_display_name_required", "提示词 ID、kind 与展示名不能为空"),
    PROMPT_ALREADY_EXISTS(409, "prompt_already_exists", "提示词 ID 已存在"),
    PROMPT_NOT_FOUND(404, "prompt_not_found", "未找到提示词"),
    DISPLAY_NAME_REQUIRED(400, "prompt_display_name_required", "展示名不能为空"),
    VERSION_NOT_FOUND(404, "prompt_version_not_found", "未找到版本"),
    DRAFT_NOT_FOUND(404, "prompt_draft_not_found", "没有可发布的草稿版本"),
    ROLLBACK_REQUIRES_PUBLISHED(400, "prompt_rollback_requires_published", "只能回滚到已发布版本"),
    INVALID_VERSION_STATUS(400, "prompt_invalid_version_status", "版本状态只能是 draft 或 published"),
    VERSION_CONTENT_REQUIRED(400, "prompt_version_content_required", "contentText 与 contentJson 至少填一项"),
    ROUTING_RULE_PARSE_FAILED(400, "routing_rule_parse_failed", "路由规则 content_json 解析失败"),
    ROUTING_RULE_INPUT_REQUIRED(400, "routing_rule_input_required", "路由规则校验需提供 id 与 contentJson");

    private final int code;
    private final String key;
    private final String message;
}
