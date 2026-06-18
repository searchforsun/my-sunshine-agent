package com.sunshine.tool.tool;

import java.util.Map;

/**
 * 可注册到 ToolRegistry 的工具处理器
 */
public interface ToolHandler {

    String name();

    String invoke(Map<String, String> params);

    /** 用户可见中文名 */
    default String displayName() {
        return name();
    }

    /** LLM function 描述 */
    default String description() {
        return "";
    }

    /** remote | local */
    default String kind() {
        return "remote";
    }

    /** Timeline phase：tool | rag */
    default String timelinePhase() {
        return "tool";
    }

    /** 输出摘要策略：hit-count | truncate */
    default String outputSummaryKind() {
        return "truncate";
    }

    /** OpenAI function parameters schema */
    default Map<String, Object> parametersSchema() {
        return Map.of("type", "object", "properties", Map.of());
    }
}
