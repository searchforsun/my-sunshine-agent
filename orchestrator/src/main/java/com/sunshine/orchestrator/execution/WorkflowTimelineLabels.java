package com.sunshine.orchestrator.execution;

import org.springframework.util.StringUtils;

/** Workflow / Plan 平台级时间线文案（非 per-workflow；per-workflow 见 Studio displayName / intentAfter） */
public final class WorkflowTimelineLabels {

    private WorkflowTimelineLabels() {
    }

    public static final String UNKNOWN_WORKFLOW = "未知工作流";
    public static final String UNKNOWN_NODE = "节点";
    public static final String SUB_AGENT_DEFAULT = "子 Agent 分析";

    public static final String TYPE_RAG = "检索知识库";
    public static final String TYPE_LLM = "综合分析";
    public static final String TYPE_AGENT = "智能体分析";
    public static final String TYPE_ANSWER = "生成回答";
    public static final String TYPE_TOOL = "调用工具";
    public static final String TYPE_JOIN = "并行汇总";
    public static final String TYPE_PARALLEL_GATEWAY = "并行分叉";
    public static final String TYPE_EXCLUSIVE_GATEWAY = "条件分支";
    public static final String TYPE_LOOP = "循环";
    public static final String TYPE_VARIABLE_ASSIGNMENT = "变量赋值";
    public static final String TYPE_PARAMETER_EXTRACTOR = "参数提取";

    public static final String AGENT_AFTER_WITH_TOOLS = "已完成 {toolCallCount} 次工具调用的综合分析";
    public static final String AGENT_AFTER_DONE = "智能体分析完成";
    public static final String AGENT_SKILL_LOADED_PREFIX = "已加载技能：{skillLabel}";

    public static final String COMPLETE = "{displayName}完成";
    public static final String HIT_COUNT = "命中 {hitCount} 条";
    public static final String SKIPPED = "已跳过";
    public static final String SKIPPED_WITH_REASON = "已跳过：{reason}";
    public static final String RETRY_SUCCESS = "（第 {attemptCount} 次尝试成功）";
    public static final String RETRY_FAILED_SUFFIX = "（已重试 {attemptCount} 次）";
    public static final String NODE_FAILED = "节点执行失败";
    public static final String ATTEMPT_COMPLETE = "完成";
    public static final String ATTEMPT_FAILED = "失败: {error}";

    public static String typeLabel(String type) {
        if (!StringUtils.hasText(type)) {
            return UNKNOWN_NODE;
        }
        return switch (type) {
            case "rag" -> TYPE_RAG;
            case "llm" -> TYPE_LLM;
            case "agent" -> TYPE_AGENT;
            case "answer" -> TYPE_ANSWER;
            case "tool" -> TYPE_TOOL;
            case "join" -> TYPE_JOIN;
            case "parallel-gateway" -> TYPE_PARALLEL_GATEWAY;
            case "exclusive-gateway" -> TYPE_EXCLUSIVE_GATEWAY;
            case "loop" -> TYPE_LOOP;
            case "variable-assignment" -> TYPE_VARIABLE_ASSIGNMENT;
            case "parameter-extractor" -> TYPE_PARAMETER_EXTRACTOR;
            default -> type;
        };
    }

    public static String apply(String template, String placeholder, String value) {
        if (!StringUtils.hasText(template)) {
            return value != null ? value : "";
        }
        return template.strip().replace(placeholder, value != null ? value : "");
    }
}
