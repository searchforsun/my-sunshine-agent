package com.sunshine.orchestrator.routing;

import com.sunshine.orchestrator.catalog.AgentCatalogIndexEntry;
import com.sunshine.orchestrator.catalog.AgentCatalogService;
import com.sunshine.orchestrator.catalog.ResourceKindFilter;
import com.sunshine.orchestrator.catalog.SkillCatalogIndexEntry;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.catalog.TenantVisibility;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.skill.SkillBindingOutcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * S-C 双阈值采纳（skill-sticky v3.8）：L3 逐项置信分 → 触发/候选/可调度池。
 *
 * <p>skill：conf &gt; trigger 且相对差距 δ 达标 → 直接触发 ≤1（进 triggered/overlay）；
 * candidate &lt; conf ≤ trigger → 候选（目录提权 + dynamicLoadable，模型经 sunshine_search_skills 显式加载）。
 * agent：conf ≥ candidate → 可调度池 Top-K（只可调度不自动委派）。
 *
 * <p>置信度仅取 L3 classifier 逐项分（禁 L2 原始相似度——未校准、跨资源不可比）；
 * 采纳后剥离内部分数参数（skillScores/agentScores 不外泄到执行/持久化层）。
 * 功能默认关闭：关闭时保持 v3.8 前 L3 收集语义，仅剥离分数参数。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillAdoptionService {

    private final AgentExecutionProperties executionProperties;
    private final SkillCatalogService skillCatalogService;
    private final AgentCatalogService agentCatalogService;

    public ExecutionPlan apply(ExecutionPlan plan, String tenantId, String sessionKind) {
        if (plan == null) {
            return null;
        }
        Map<String, Double> skillScores = visibleSkillScores(plan.skillScores(), tenantId, sessionKind);
        Map<String, Double> agentScores = visibleAgentScores(plan.agentScores(), sessionKind);
        if (!adoptionEnabled() || plan.mode() == ExecutionMode.WORKFLOW
                || (skillScores.isEmpty() && agentScores.isEmpty())) {
            return stripScores(plan);
        }
        AgentExecutionProperties.React.SkillAdoption cfg = executionProperties.getReact().getSkillAdoption();
        Map<String, String> params = new LinkedHashMap<>(plan.params() != null ? plan.params() : Map.of());
        params.remove(ExecutionPlan.PARAM_SKILL_SCORES);
        params.remove(ExecutionPlan.PARAM_AGENT_SCORES);
        List<RoutingTrace> traces = plan.routingTraces() != null
                ? new ArrayList<>(plan.routingTraces()) : new ArrayList<>();
        boolean adoptedSkills = adoptSkills(plan, cfg, skillScores, params, traces);
        boolean adoptedAgents = adoptAgents(plan, cfg, agentScores, params, traces);
        if (!adoptedSkills && !adoptedAgents) {
            return stripScores(plan);
        }
        log.info("[SkillAdoption] adopted: triggered={} candidates={} agents={}",
                params.get(ExecutionPlan.PARAM_SKILL_IDS),
                params.get(ExecutionPlan.PARAM_CANDIDATE_SKILL_IDS),
                params.get(ExecutionPlan.PARAM_AGENT_IDS));
        return new ExecutionPlan(plan.mode(), plan.workflowId(),
                params.isEmpty() ? Map.of() : Map.copyOf(params),
                plan.reason(), plan.ruleId(), List.copyOf(traces));
    }

    /** skill 双阈值：最高分过 trigger 且 δ 达标 → 触发 ≤1；其余 (candidate, trigger] 进候选 Top-K */
    private boolean adoptSkills(ExecutionPlan plan, AgentExecutionProperties.React.SkillAdoption cfg,
            Map<String, Double> scores, Map<String, String> params, List<RoutingTrace> traces) {
        if (scores.isEmpty()) {
            return false;
        }
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(scores.entrySet());
        sorted.sort(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()));
        Set<String> triggered = new LinkedHashSet<>(plan.triggeredSkillIds());
        String promoted = null;
        if (triggered.isEmpty()) {
            Map.Entry<String, Double> top = sorted.get(0);
            if (top.getValue() > cfg.getTrigger() && deltaReached(sorted, cfg.getDelta())) {
                promoted = top.getKey();
                triggered.add(promoted);
                params.put(ExecutionPlan.PARAM_SKILL_IDS, promoted);
                params.put(SkillBindingOutcome.PARAM_SKILL, promoted);
            }
        }
        List<String> candidates = new ArrayList<>();
        for (Map.Entry<String, Double> e : sorted) {
            if (candidates.size() >= cfg.getCandidateTopK()) {
                break;
            }
            double conf = e.getValue();
            if (conf <= cfg.getCandidate() || conf > cfg.getTrigger() || triggered.contains(e.getKey())) {
                continue;
            }
            candidates.add(e.getKey());
        }
        if (!candidates.isEmpty()) {
            params.put(ExecutionPlan.PARAM_CANDIDATE_SKILL_IDS, String.join(",", candidates));
        }
        if (promoted == null && candidates.isEmpty()) {
            return false;
        }
        traces.add(RoutingTrace.of("L3", "意图识别",
                promoted != null
                        ? "高置信触发技能「" + promoted + "」"
                        : "识别出候选技能，按需动态加载"));
        return true;
    }

    /** agent 单阈值：conf ≥ candidate 全量进可调度池（Top-K，降序），只可调度不自动委派 */
    private boolean adoptAgents(ExecutionPlan plan, AgentExecutionProperties.React.SkillAdoption cfg,
            Map<String, Double> scores, Map<String, String> params, List<RoutingTrace> traces) {
        if (scores.isEmpty() || !plan.schedulableAgentIds().isEmpty()) {
            return false;
        }
        List<String> pool = scores.entrySet().stream()
                .filter(e -> e.getValue() >= cfg.getCandidate())
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .map(Map.Entry::getKey)
                .limit(cfg.getAgentTopK())
                .toList();
        if (pool.isEmpty()) {
            return false;
        }
        params.put(ExecutionPlan.PARAM_AGENT_IDS, String.join(",", pool));
        traces.add(RoutingTrace.of("L3", "意图识别", "识别出可委派助手（按需调度）"));
        return true;
    }

    /** 相对差距 δ：(最高-次高)/最高 ≥ delta；仅 1 项计分时无竞争者，视为达标 */
    private static boolean deltaReached(List<Map.Entry<String, Double>> sorted, double delta) {
        if (sorted.size() < 2) {
            return true;
        }
        double top = sorted.get(0).getValue();
        if (top <= 0) {
            return false;
        }
        double second = sorted.get(1).getValue();
        return (top - second) / top >= delta;
    }

    private Map<String, Double> visibleSkillScores(Map<String, Double> scores, String tenantId, String sessionKind) {
        if (scores.isEmpty()) {
            return Map.of();
        }
        String effectiveTenant = TenantVisibility.normalize(tenantId);
        Map<String, Double> visible = new LinkedHashMap<>();
        scores.forEach((id, conf) -> skillCatalogService.findIndex(id)
                .filter(SkillCatalogIndexEntry::enabled)
                .filter(e -> ResourceKindFilter.matches(e.kind(), sessionKind))
                .filter(e -> TenantVisibility.visible(e.tenantId(), effectiveTenant))
                .ifPresent(e -> visible.put(e.id(), conf)));
        return visible.isEmpty() ? Map.of() : Map.copyOf(visible);
    }

    private Map<String, Double> visibleAgentScores(Map<String, Double> scores, String sessionKind) {
        if (scores.isEmpty()) {
            return Map.of();
        }
        Map<String, Double> visible = new LinkedHashMap<>();
        scores.forEach((id, conf) -> agentCatalogService.findIndex(id)
                .filter(AgentCatalogIndexEntry::enabled)
                .filter(e -> ResourceKindFilter.matches(e.kind(), sessionKind))
                .ifPresent(e -> visible.put(e.id(), conf)));
        return visible.isEmpty() ? Map.of() : Map.copyOf(visible);
    }

    /** 剥离内部分数参数（含关闭/轨 B 路径）：分数是采纳输入，不进执行与持久化层 */
    private static ExecutionPlan stripScores(ExecutionPlan plan) {
        if (plan == null || plan.params() == null
                || (!plan.params().containsKey(ExecutionPlan.PARAM_SKILL_SCORES)
                        && !plan.params().containsKey(ExecutionPlan.PARAM_AGENT_SCORES))) {
            return plan;
        }
        Map<String, String> params = new LinkedHashMap<>(plan.params());
        params.remove(ExecutionPlan.PARAM_SKILL_SCORES);
        params.remove(ExecutionPlan.PARAM_AGENT_SCORES);
        return new ExecutionPlan(plan.mode(), plan.workflowId(),
                params.isEmpty() ? Map.of() : Map.copyOf(params),
                plan.reason(), plan.ruleId(), plan.routingTraces());
    }

    private boolean adoptionEnabled() {
        AgentExecutionProperties.React react = executionProperties.getReact();
        return react != null && react.getSkillAdoption() != null && react.getSkillAdoption().isEnabled();
    }
}
