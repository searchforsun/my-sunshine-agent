package com.sunshine.common.model;

import java.util.Arrays;
import java.util.Optional;

/**
 * 模型场景键枚举（SSOT）。禁止自定义 scene_key；扩展时仅在此追加，并同步 SQL 种子对账。
 */
public enum ModelSceneKey {
    DEFAULT("default", "通用默认", "未单独绑定的调用面；chat 用户未选模型时的缺省"),
    CHAT("chat", "对话主循环", "对话主 Agent 缺省主/备模型；用户可选集另由 user_selectable 控制"),
    INTENT("intent", "意图分类", "IntentRouter 分类请求所用模型"),
    PLANNER("planner", "规划", "Planner / 规划类 LLM 调用"),
    TITLE("title", "会话标题", "会话标题生成"),
    SUBAGENT("subagent", "子代理缺省", "spawn 未带 modelConfig 时的缺省模型");

    private final String key;
    private final String label;
    private final String description;

    ModelSceneKey(String key, String label, String description) {
        this.key = key;
        this.label = label;
        this.description = description;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public static Optional<ModelSceneKey> fromKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.strip();
        return Arrays.stream(values())
                .filter(v -> v.key.equals(normalized))
                .findFirst();
    }

    public static boolean isKnown(String raw) {
        return fromKey(raw).isPresent();
    }

    public static String knownKeysCsv() {
        return String.join(", ", Arrays.stream(values()).map(ModelSceneKey::key).toList());
    }

    public String display() {
        return label + "（" + key + "）";
    }

    @Override
    public String toString() {
        return key;
    }
}
