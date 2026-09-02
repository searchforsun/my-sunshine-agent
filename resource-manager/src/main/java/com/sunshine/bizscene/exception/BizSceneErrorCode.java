package com.sunshine.bizscene.exception;

import com.sunshine.common.core.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum BizSceneErrorCode implements ErrorCode {
    CODE_REQUIRED(400, "biz_scene_code_required", "业务场景码不能为空"),
    DISPLAY_NAME_REQUIRED(400, "biz_scene_display_name_required", "业务场景名称不能为空"),
    SCENE_ALREADY_EXISTS(409, "biz_scene_already_exists", "业务场景码已存在"),
    SCENE_NOT_FOUND(404, "biz_scene_not_found", "未找到业务场景"),
    SCENE_NOT_ACTIVE(400, "biz_scene_not_active", "业务场景不存在或已禁用，不可创建/绑定"),
    INVALID_STATUS(400, "biz_scene_invalid_status", "状态仅支持 active|disabled|pending_review|rejected|auto_cleaned"),
    INVALID_SOURCE(400, "biz_scene_invalid_source", "来源仅支持 manual|auto"),
    INVALID_CODE_FORMAT(400, "biz_scene_invalid_code", "场景码须匹配 [a-z][a-z0-9_-]{2,48}"),
    POLICY_NOT_FOUND(404, "biz_scene_policy_not_found", "未找到该规则");

    private final int code;
    private final String key;
    private final String message;
}
