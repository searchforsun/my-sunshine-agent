package com.sunshine.orchestrator.plan;

import org.springframework.util.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plan 校验失败 → Replan 结构化反馈（SSOT：错误码 + 问题 + 修正指引）。
 * 供 WorkflowPlanner 注入 user message，避免模型仅见短句无法自修。
 */
public final class PlanValidationFeedback {

    private static final Pattern CROSS_FRAME = Pattern.compile("禁止跨框边 ([^→]+)→(.+)");
    private static final Pattern CROSS_LOOP = Pattern.compile("禁止跨不同 loop 的边 ([^→]+)→(.+)");
    private static final Pattern LOOP_NO_BODY = Pattern.compile("loop 节点 ([\\w-]+) 须包含至少一个 body 节点");
    private static final Pattern LOOP_OUT_DEGREE = Pattern.compile("loop 节点 ([\\w-]+) 外图出度须为 1");
    private static final Pattern LOOP_COND_OP = Pattern.compile("loop 节点 ([\\w-]+) 的 condition\\.op 非法: (.+)");
    private static final Pattern JOIN_IN = Pattern.compile("join 节点 ([\\w-]+) 入度须 ≥ 2");
    private static final Pattern XGW_OUT = Pattern.compile("exclusive-gateway 节点 ([\\w-]+) 出度须 ≥ 2");
    private static final Pattern XGW_DEFAULT = Pattern.compile("exclusive-gateway 节点 ([\\w-]+) 须恰好 1 条 default 出边");
    private static final Pattern UNKNOWN_TOOL = Pattern.compile("未知工具: (.+)");
    private static final Pattern ILLEGAL_TYPE = Pattern.compile("Planner 非法节点 type: (.+)");
    private static final Pattern ANSWER_EDGE = Pattern.compile("Planner edges 勿指向 answer");
    private static final Pattern BODY_LINEAR = Pattern.compile("loop body (.+)");

    private PlanValidationFeedback() {
    }

    /** Replan user message 中的 {{error}} 替换内容 */
    public static String formatForReplan(String rawError) {
        if (!StringUtils.hasText(rawError)) {
            return "【错误码】UNKNOWN\n【问题】未知校验错误\n【修正指引】检查 nodes/edges 是否符合 Planner 契约后重输出一行 JSON。";
        }
        Issue issue = classify(rawError.strip());
        return """
                【错误码】%s
                【问题】%s
                【修正指引】
                %s""".formatted(issue.code(), issue.problem(), issue.fixHint());
    }

    private static Issue classify(String err) {
        Matcher m = CROSS_FRAME.matcher(err);
        if (m.find()) {
            String from = m.group(1).strip();
            String to = m.group(2).strip();
            return new Issue("LOOP_CROSS_FRAME",
                    "edge " + from + "→" + to + " 跨 loop 框内外（loop 容器与 parentId body 之间禁止连边）",
                    """
                    1. 删除 edge %s→%s
                    2. 若 %s 为 loop、%s 为 body：body 保留 "parentId":"%s"，外图 edges 只保留 start→%s
                    3. 单 body 时勿写 loop→body 或框内 edges；多 body 时框内才写 b1→b2（同 parentId）
                    4. 外图勿连 answer（引擎自动拼接）""".formatted(from, to, from, to, from, from));
        }
        m = CROSS_LOOP.matcher(err);
        if (m.find()) {
            return new Issue("LOOP_CROSS_LOOP",
                    "edge " + m.group(1) + "→" + m.group(2) + " 跨不同 loop 容器",
                    "每个 loop 框内 body 独立成链；禁止 body 节点跨 parentId 连边。");
        }
        m = LOOP_NO_BODY.matcher(err);
        if (m.find()) {
            String loopId = m.group(1);
            return new Issue("LOOP_EMPTY_BODY",
                    "loop " + loopId + " 无框内 body",
                    """
                    1. 至少添加 1 个 rag/tool/agent 节点，设 "parentId":"%s"
                    2. 外图 edges：start→%s；勿写 %s→body 跨框边
                    3. 单 body 可省略框内 edges""".formatted(loopId, loopId, loopId));
        }
        m = LOOP_OUT_DEGREE.matcher(err);
        if (m.find()) {
            String loopId = m.group(1);
            return new Issue("LOOP_OUTER_DEGREE",
                    "loop " + loopId + " 外图出度不为 1（可能连了 body 或多余外图边）",
                    """
                    1. 外图从 loop 出发的边只能 1 条（normalize 后接 answer，Planner 勿连 answer）
                    2. 删除 loop→body 跨框边；body 仅用 parentId 归属
                    3. 勿额外写 loop→n2 串行外图链""".formatted());
        }
        m = LOOP_COND_OP.matcher(err);
        if (m.find()) {
            return new Issue("LOOP_CONDITION_OP",
                    "loop " + m.group(1) + " 的 condition.op=" + m.group(2) + " 非法",
                    "condition.op 仅允许 empty | not_empty | contains | eq（勿用 == 或 =）；contains/eq 须填 condition.right。");
        }
        if (BODY_LINEAR.matcher(err).find()) {
            return new Issue("LOOP_BODY_CHAIN",
                    err,
                    """
                    loop 框内 body 须为单链：恰好 1 个入口（入度 0），节点依次 b1→b2→…，无分叉无环。
                    多 body 时在 edges 写框内链；单 body 省略框内 edges。""");
        }
        m = JOIN_IN.matcher(err);
        if (m.find()) {
            return new Issue("PARALLEL_JOIN_IN",
                    "join " + m.group(1) + " 入度不足",
                    "并行须 parallel-gateway → 多分支 rag/tool → join；各分支独立连 join，勿串行 n1→n2→join。");
        }
        if (err.contains("并行分叉点") || err.contains("并行分支")) {
            return new Issue("PARALLEL_TOPOLOGY", err,
                    "并行结构：start→parallel-gateway→(≥2 分支)→join→（引擎接 answer）；分支头不能是 join。");
        }
        m = XGW_DEFAULT.matcher(err);
        if (m.find()) {
            return new Issue("EXCLUSIVE_DEFAULT",
                    "exclusive-gateway " + m.group(1) + " default 出边数量不对",
                    "出边须 ≥2，且恰好 1 条 \"default\":true；其余出边带 condition（left/op/right）。");
        }
        m = XGW_OUT.matcher(err);
        if (m.find()) {
            return new Issue("EXCLUSIVE_OUT",
                    "exclusive-gateway " + m.group(1) + " 出度不足",
                    "条件分支须 ≥2 条出边：一条 condition 命中臂 + 一条 default:true 兜底臂。");
        }
        if (err.contains("exclusive-gateway")) {
            return new Issue("EXCLUSIVE_TOPOLOGY", err,
                    "condition/default 只能出现在 exclusive-gateway 出边；网关出度 ≥2，恰好 1 default。");
        }
        m = UNKNOWN_TOOL.matcher(err);
        if (m.find()) {
            return new Issue("UNKNOWN_TOOL",
                    "params.tool=" + m.group(1) + " 不在 Tool 目录",
                    "从下方 Tool 目录选真实 Catalog ID（如 sdk__sunshine-finance__list_finance_messages）。");
        }
        m = ILLEGAL_TYPE.matcher(err);
        if (m.find()) {
            return new Issue("ILLEGAL_NODE_TYPE",
                    "非法 type=" + m.group(1),
                    "仅允许 rag | tool | agent | parallel-gateway | join | exclusive-gateway | loop；勿输出 start/answer。");
        }
        if (ANSWER_EDGE.matcher(err).find()) {
            return new Issue("ANSWER_IN_EDGES",
                    "edges 指向 answer",
                    "Planner 勿输出 answer 节点，也勿写任何 edge.to=answer；引擎自动拼接终态 answer。");
        }
        if (err.contains("agent 节点") && err.contains("缺少 params.context")) {
            return new Issue("AGENT_CONTEXT",
                    err,
                    "agent 须 params.context 引用上游 {{n*.output}}，params.query 写子任务；无上游时用 rag/tool 先产出 output。");
        }
        if (err.contains("缺少 displayName")) {
            return new Issue("MISSING_DISPLAY_NAME", err, "每个节点补 displayName（中文短名）。");
        }
        if (err.contains("节点数超过上限") || err.contains("业务节点数超过上限")) {
            return new Issue("TOO_MANY_NODES", err, "合并步骤或去掉冗余节点，业务节点 ≤ 8。");
        }
        return new Issue("VALIDATION_FAILED", err,
                "对照 Planner 拓扑契约修正 nodes/edges 后重输出一行 JSON。");
    }

    private record Issue(String code, String problem, String fixHint) {
    }
}
