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
    SCENE_NOT_ACTIVE(400, "biz_scene_not_active", "业务场景不存在或已退役，不可创建/绑定"),
    INVALID_STATUS(400, "biz_scene_invalid_status", "状态仅支持 active|retired"),
    POLICY_NOT_FOUND(404, "biz_scene_policy_not_found", "未找到该规则");

    private final int code;
    private final String key;
    private final String message;
}
