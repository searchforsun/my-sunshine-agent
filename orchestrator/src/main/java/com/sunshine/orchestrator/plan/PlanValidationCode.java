package com.sunshine.orchestrator.plan;

/** Plan 校验错误码 SSOT；Replan 反馈与 Validator 共用。 */
public enum PlanValidationCode {
    UNKNOWN,
    VALIDATION_FAILED,
    NODES_EMPTY,
    LOOP_CROSS_FRAME,
    LOOP_CROSS_LOOP,
    LOOP_EMPTY_BODY,
    LOOP_OUTER_DEGREE,
    LOOP_CONDITION_OP,
    LOOP_BODY_CHAIN,
    PARALLEL_JOIN_IN,
    PARALLEL_TOPOLOGY,
    EXCLUSIVE_DEFAULT,
    EXCLUSIVE_OUT,
    EXCLUSIVE_TOPOLOGY,
    UNKNOWN_TOOL,
    ILLEGAL_NODE_TYPE,
    ANSWER_IN_EDGES,
    AGENT_CONTEXT,
    MISSING_DISPLAY_NAME,
    TOO_MANY_NODES,
    TOO_MANY_TOTAL_NODES;

    /** 默认修正指引；动态占位（如 {max}）由 Validator 在构造 Issue 时替换。 */
    public String defaultFixHint() {
        return switch (this) {
            case UNKNOWN -> "检查 nodes/edges 是否符合 Planner 契约后重输出一行 JSON。";
            case VALIDATION_FAILED -> "对照 Planner 拓扑契约修正 nodes/edges 后重输出一行 JSON。";
            case NODES_EMPTY -> "至少输出 1 个业务或路由节点（rag/tool/agent/parallel-gateway/join/exclusive-gateway/loop）。";
            case LOOP_CROSS_FRAME -> """
                    1. 删除跨 loop 框内外的 edge
                    2. body 保留 parentId 归属 loop；外图 edges 只保留 start→loop
                    3. 单 body 时勿写 loop→body 或框内 edges；多 body 时框内才写 b1→b2（同 parentId）
                    4. 外图勿连 answer（引擎自动拼接）""";
            case LOOP_CROSS_LOOP -> "每个 loop 框内 body 独立成链；禁止 body 节点跨 parentId 连边。";
            case LOOP_EMPTY_BODY -> """
                    1. 至少添加 1 个 rag/tool/agent 节点，设 parentId 指向 loop
                    2. 外图 edges：start→loop；勿写 loop→body 跨框边
                    3. 单 body 可省略框内 edges""";
            case LOOP_OUTER_DEGREE -> """
                    1. 外图从 loop 出发的边只能 1 条（normalize 后接 answer，Planner 勿连 answer）
                    2. 删除 loop→body 跨框边；body 仅用 parentId 归属
                    3. 勿额外写 loop→n2 串行外图链""";
            case LOOP_CONDITION_OP -> "condition.op 仅允许 empty | not_empty | contains | eq（勿用 == 或 =）；contains/eq 须填 condition.right。";
            case LOOP_BODY_CHAIN -> """
                    loop 框内 body 须为单链：恰好 1 个入口（入度 0），节点依次 b1→b2→…，无分叉无环。
                    多 body 时在 edges 写框内链；单 body 省略框内 edges。""";
            case PARALLEL_JOIN_IN -> "并行须 parallel-gateway → 多分支 rag/tool → join；各分支独立连 join，勿串行 n1→n2→join。";
            case PARALLEL_TOPOLOGY -> "并行结构：start→parallel-gateway→(≥2 分支)→join→（引擎接 answer）；分支头不能是 join。";
            case EXCLUSIVE_DEFAULT -> "出边须 ≥2，且恰好 1 条 \"default\":true；其余出边带 condition（left/op/right）。";
            case EXCLUSIVE_OUT -> "条件分支须 ≥2 条出边：一条 condition 命中臂 + 一条 default:true 兜底臂。";
            case EXCLUSIVE_TOPOLOGY -> "condition/default 只能出现在 exclusive-gateway 出边；网关出度 ≥2，恰好 1 default。";
            case UNKNOWN_TOOL -> "从下方 Tool 目录选真实 Catalog ID（如 sdk__sunshine-finance__list_my_expenses）。";
            case ILLEGAL_NODE_TYPE -> "仅允许 rag | tool | agent | parallel-gateway | join | exclusive-gateway | loop；勿输出 start/answer。";
            case ANSWER_IN_EDGES -> "Planner 勿输出 answer 节点，也勿写任何 edge.to=answer；引擎自动拼接终态 answer。";
            case AGENT_CONTEXT -> "agent 须 params.context 引用上游 {{n*.output}}，params.query 写子任务；无上游时用 rag/tool 先产出 output。";
            case MISSING_DISPLAY_NAME -> "每个节点补 displayName（中文短名）。";
            case TOO_MANY_NODES -> "合并步骤或去掉冗余节点，业务节点 ≤ {max}。";
            case TOO_MANY_TOTAL_NODES -> "合并步骤或去掉冗余路由/业务节点，节点总数 ≤ {max}。";
        };
    }
}
