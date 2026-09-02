package com.sunshine.orchestrator.routing;

import com.sunshine.orchestrator.skill.SkillBindingOutcome;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 意图识别结果 — 路由层输出，供 ExecutionDispatcher 分发。
 * params 承载 RoutingResult 语义：{@link #PARAM_SKILL_IDS}（本轮已触发集）与
 * {@link #PARAM_AGENT_IDS}（本轮可调度池），单数 {@code skill} 兼容旧契约。
 */
public record ExecutionPlan(
        ExecutionMode mode,
        String workflowId,
        Map<String, String> params,
        String reason,
        String ruleId,
        List<RoutingTrace> routingTraces
) {
    /** 本轮已触发 skill 集（逗号分隔；skill-sticky S-0/S-1） */
    public static final String PARAM_SKILL_IDS = "skillIds";
    /** 本轮可调度 agent 集（逗号分隔；skill-sticky S-0/S-1） */
    public static final String PARAM_AGENT_IDS = "agentIds";
    /** 本轮候选 skill 集（逗号分隔；candidate < 置信 ≤ trigger，可动态加载；skill-sticky S-C） */
    public static final String PARAM_CANDIDATE_SKILL_IDS = "candidateSkillIds";
    /** L3 每个 skill 的置信分（"id=conf,id=conf"；S-C 双阈值采纳的原始输入，采纳后剥离） */
    public static final String PARAM_SKILL_SCORES = "skillScores";
    /** L3 每个 agent 的置信分（"id=conf,id=conf"；S-C 可调度池采纳的原始输入，采纳后剥离） */
    public static final String PARAM_AGENT_SCORES = "agentScores";
    public ExecutionPlan(ExecutionMode mode, String workflowId, Map<String, String> params, String reason) {
        this(mode, workflowId, params, reason, null, null);
    }

    public ExecutionPlan(
            ExecutionMode mode,
            String workflowId,
            Map<String, String> params,
            String reason,
            String ruleId) {
        this(mode, workflowId, params, reason, ruleId, null);
    }

    public static ExecutionPlan reactFallback(String reason) {
        return new ExecutionPlan(ExecutionMode.FAST, null, Map.of(), reason);
    }

    /** 写入 DB / Generation / 审计的简短标签（协议 wire：fast|pro|workflow:…） */
    public String intentLabel() {
        return switch (mode) {
            case WORKFLOW -> "workflow:" + (workflowId != null ? workflowId : "unknown");
            case FAST -> "fast";
            case PRO -> "pro";
        };
    }

    /** 本轮已触发 skill 集：优先多值 {@code skillIds}，回退单数 {@code skill}（可空） */
    public List<String> triggeredSkillIds() {
        if (params == null) {
            return List.of();
        }
        String multi = params.get(PARAM_SKILL_IDS);
        if (StringUtils.hasText(multi)) {
            return csvToList(multi);
        }
        String single = params.get(SkillBindingOutcome.PARAM_SKILL);
        return StringUtils.hasText(single) ? List.of(single.strip()) : List.of();
    }

    /** 本轮可调度 agent 集（可空） */
    public List<String> schedulableAgentIds() {
        if (params == null) {
            return List.of();
        }
        String agents = params.get(PARAM_AGENT_IDS);
        return StringUtils.hasText(agents) ? csvToList(agents) : List.of();
    }

    /** 本轮候选 skill 集（可空；candidate < 置信 ≤ trigger，仅目录提权 + 可动态加载，不进 overlay） */
    public List<String> candidateSkillIds() {
        if (params == null) {
            return List.of();
        }
        String candidates = params.get(PARAM_CANDIDATE_SKILL_IDS);
        return StringUtils.hasText(candidates) ? csvToList(candidates) : List.of();
    }

    /**
     * L3 每个 skill 的置信分（保序；id → conf）。S-C 双阈值采纳的原始输入。
     * 格式 "id=conf,id=conf"；解析失败项跳过。
     */
    public Map<String, Double> skillScores() {
        return parseScores(params != null ? params.get(PARAM_SKILL_SCORES) : null);
    }

    /** L3 每个 agent 的置信分（保序；id → conf）。可调度池采纳的原始输入。 */
    public Map<String, Double> agentScores() {
        return parseScores(params != null ? params.get(PARAM_AGENT_SCORES) : null);
    }

    private static Map<String, Double> parseScores(String raw) {
        if (!StringUtils.hasText(raw)) {
            return Map.of();
        }
        Map<String, Double> out = new java.util.LinkedHashMap<>();
        for (String pair : raw.split(",")) {
            int eq = pair.indexOf('=');
            if (eq <= 0 || eq == pair.length() - 1) {
                continue;
            }
            String id = pair.substring(0, eq).strip();
            if (!StringUtils.hasText(id)) {
                continue;
            }
            try {
                out.put(id, Double.parseDouble(pair.substring(eq + 1).strip()));
            } catch (NumberFormatException ignored) {
                // 单项置信分非法 → 跳过，不阻断其余采纳
            }
        }
        return out.isEmpty() ? Map.of() : Map.copyOf(out);
    }

    private static List<String> csvToList(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::strip)
                .filter(StringUtils::hasText)
                .toList();
    }
}
