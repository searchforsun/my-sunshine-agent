package com.sunshine.orchestrator.plan.harness;

import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Worker forWorker 上下文：稳定前缀进 {@link AssembledContext#projectGuideBlock()}；
 * 动态 upstream handoff 由 {@link #buildDynamicQuery} 拼进 {@code AgentRunRequest.query}。
 */
@Component
@RequiredArgsConstructor
public class WorkerContextFactory {

    public static final String CATALOG_ID = "harness.worker";

    private final PromptCatalogHolder catalogHolder;

    public AssembledContext build(TaskItem task) {
        String stable = buildStablePrefix(task);
        return AssembledContext.forWorker(stable, "");
    }

    /**
     * 动态段：用户问题 + dependsOn 已完成 handoff（来自 rounds / NodeResult.summary）。
     * 禁止写入 projectGuideBlock。
     */
    public String buildDynamicQuery(PlanNotebook nb, TaskItem task) {
        StringBuilder sb = new StringBuilder();
        String userQ = nb != null && StringUtils.hasText(nb.getUserQuery())
                ? nb.getUserQuery().strip()
                : "";
        if (StringUtils.hasText(userQ)) {
            sb.append("## 用户问题\n").append(userQ).append('\n');
        }
        Map<String, String> handoffs = collectUpstreamHandoffs(nb, task);
        if (!handoffs.isEmpty()) {
            sb.append("\n## 上游 handoff\n");
            for (Map.Entry<String, String> e : handoffs.entrySet()) {
                sb.append("### ").append(e.getKey()).append('\n')
                        .append(e.getValue()).append('\n');
            }
        }
        if (task != null && StringUtils.hasText(task.label())) {
            sb.append("\n## 当前单元\n").append(task.label().strip()).append('\n');
        }
        return sb.toString().strip();
    }

    String buildStablePrefix(TaskItem task) {
        String template = catalogHolder.requireText(CATALOG_ID);
        String taskGoal = task != null && task.label() != null ? task.label() : "";
        String constraints = task != null && task.constraints() != null ? task.constraints() : "";
        String expectedOutput = task != null && task.expectedOutput() != null ? task.expectedOutput() : "";
        String successCriteria = task != null && task.successCriteria() != null ? task.successCriteria() : "";
        return template
                .replace("{{taskGoal}}", taskGoal)
                .replace("{{constraints}}", constraints)
                .replace("{{expectedOutput}}", expectedOutput)
                .replace("{{successCriteria}}", successCriteria);
    }

    /** dependsOn 顺序；同一 taskId 取 rounds 中最新一条非空 summary。 */
    private static Map<String, String> collectUpstreamHandoffs(PlanNotebook nb, TaskItem task) {
        Map<String, String> out = new LinkedHashMap<>();
        if (nb == null || task == null || task.dependsOn() == null || task.dependsOn().isEmpty()) {
            return out;
        }
        Map<String, String> byTaskId = new LinkedHashMap<>();
        for (RoundRecord round : nb.getRounds()) {
            if (round == null || round.task() == null || round.nodeResults() == null) {
                continue;
            }
            String tid = round.task().taskId();
            if (!StringUtils.hasText(tid)) {
                continue;
            }
            String summary = pickSummary(round.nodeResults(), tid);
            if (StringUtils.hasText(summary)) {
                byTaskId.put(tid, summary.strip());
            }
        }
        List<String> deps = new ArrayList<>(task.dependsOn());
        for (String dep : deps) {
            if (!StringUtils.hasText(dep)) {
                continue;
            }
            String key = dep.strip();
            String summary = byTaskId.get(key);
            if (StringUtils.hasText(summary)) {
                out.put(key, summary);
            }
        }
        return out;
    }

    private static String pickSummary(List<NodeResult> results, String taskId) {
        String fallback = null;
        for (NodeResult nr : results) {
            if (nr == null || !StringUtils.hasText(nr.summary())) {
                continue;
            }
            if (taskId.equals(nr.nodeId())) {
                return nr.summary();
            }
            if (fallback == null) {
                fallback = nr.summary();
            }
        }
        return fallback;
    }
}
