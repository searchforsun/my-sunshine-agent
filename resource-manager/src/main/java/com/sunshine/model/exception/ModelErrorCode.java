package com.sunshine.model.exception;

import com.sunshine.common.core.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ModelErrorCode implements ErrorCode {
    PROVIDER_KEY_REQUIRED(400, "model_provider_key_required", "厂商标识不能为空"),
    PROVIDER_DISPLAY_NAME_REQUIRED(400, "model_provider_display_name_required", "厂商展示名不能为空"),
    PROVIDER_BASE_URL_REQUIRED(400, "model_provider_base_url_required", "厂商 baseUrl 不能为空"),
    PROVIDER_ALREADY_EXISTS(409, "model_provider_already_exists", "厂商标识已存在"),
    PROVIDER_NOT_FOUND(404, "model_provider_not_found", "未找到模型厂商"),
    MODEL_NAME_REQUIRED(400, "model_name_required", "模型名不能为空"),
    MODEL_DISPLAY_NAME_REQUIRED(400, "model_display_name_required", "模型展示名不能为空"),
    MODEL_PROVIDER_REQUIRED(400, "model_definition_provider_required", "模型所属厂商不能为空"),
    MODEL_ALREADY_EXISTS(409, "model_already_exists", "模型名在租户内已存在"),
    MODEL_NOT_FOUND(404, "model_not_found", "未找到模型定义"),
    MODEL_NOT_ENABLED(400, "model_not_enabled", "模型未启用，不能绑定到场景"),
    SCENE_KEY_REQUIRED(400, "model_scene_key_required", "场景键不能为空"),
    SCENE_KEY_INVALID(400, "model_scene_key_invalid", "场景键必须为系统枚举值，禁止自定义"),
    SCENE_PRIMARY_REQUIRED(400, "model_scene_primary_required", "场景主模型不能为空"),
    SCENE_NOT_FOUND(404, "model_scene_not_found", "未找到场景绑定"),
    SCENE_REQUEST_INVALID(400, "model_scene_request_invalid", "场景绑定请求格式无效"),
    ROUTE_CALL_SITE_REQUIRED(400, "model_route_call_site_required", "调用点不能为空"),
    ROUTE_CALL_SITE_INVALID(400, "model_route_call_site_invalid", "调用点必须为系统枚举值，禁止自定义"),
    ROUTE_MODELS_REQUIRED(400, "model_route_models_required", "候选模型池不能为空"),
    ROUTE_STRATEGY_INVALID(400, "model_route_strategy_invalid", "路由策略仅支持 first-available"),
    ROUTE_NOT_FOUND(404, "model_route_not_found", "未找到路由策略"),
    CRYPTO_FAILED(500, "model_crypto_failed", "模型密钥加解密失败");

    private final int code;
    private final String key;
    private final String message;
}
