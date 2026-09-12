package com.sunshine.model.dto;

/** 调用点枚举元数据（只读；供前端下拉与描述展示，与 ModelSceneKeyMeta 同构） */
public record ModelRouteKeyMeta(
        String key,
        String label,
        String description
) {
}
