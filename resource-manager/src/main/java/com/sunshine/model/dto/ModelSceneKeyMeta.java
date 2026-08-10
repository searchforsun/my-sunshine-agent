package com.sunshine.model.dto;

/** 场景枚举元数据（只读；供前端下拉与描述展示） */
public record ModelSceneKeyMeta(
        String sceneKey,
        String label,
        String description
) {
}
