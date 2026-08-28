package com.sunshine.common.model;

import java.util.Arrays;
import java.util.Optional;

/**
 * LLM 调用点枚举（phase5 5.3，SSOT）。禁止自定义 call_site；
 * 扩展时仅在此追加，并同步 20-sunshine-model-registry.sql 种子对账。
 * <p>
 * 与会话形态 kind（chat/task）、业务域 biz_scene、执行模式 executionMode 硬隔离，
 * 禁止复用任一字段承载另一轴语义。
 */
public enum CallSiteKey {
    CHAT("chat", "对话主循环", "聊天主 Agent 调用"),
    PLAN("plan", "规划", "Planner 规划 / 自判调用"),
    WORKER("worker", "Worker 执行", "Planner-Executor Worker 工具执行调用"),
    TOOL_CALL("tool-call", "工具调用", "独立工具调用回合"),
    REWRITE("rewrite", "意图/改写", "意图分类、Query 改写等前置调用"),
    SUMMARIZE("summarize", "摘要/内部辅助", "L1/L2/L3 摘要、语义抽取、审计等内部辅助调用"),
    SUBAGENT("subagent", "子代理", "spawn 子智能体调用");

    private final String key;
    private final String label;
    private final String description;

    CallSiteKey(String key, String label, String description) {
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

    public static Optional<CallSiteKey> fromKey(String raw) {
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
        return String.join(", ", Arrays.stream(values()).map(CallSiteKey::key).toList());
    }
}
