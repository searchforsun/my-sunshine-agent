package com.sunshine.orchestrator.plan;

import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 动态 Plan 的 answer 节点 prompt — Catalog {@code answer.template} + 按拓扑注入上游 {@code {{n*.output}}}。
 * Planner 不负责撰写 answer 话术，避免 meta 指令进入 reasoning。
 * 缺 Catalog → 空串 + warn（禁止 Java 模板兜底）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlanAnswerPromptAssembler {

    static final String UPSTREAM_PLACEHOLDER = "{{plan.upstream}}";

    private final PromptCatalogHolder catalogHolder;

    /** 为 answer 节点写入 params.prompt（覆盖 Planner 自带 prompt） */
    public PlanJson apply(PlanJson plan) {
        List<String> order = PlanLinearizer.linearOrder(plan);
        String answerId = findAnswerNodeId(plan, order);
        if (answerId == null) {
            return plan;
        }
        int answerIdx = order.indexOf(answerId);
        List<String> upstreamIds = answerIdx > 0 ? order.subList(0, answerIdx) : List.of();
        String prompt = buildPrompt(plan, upstreamIds);
        List<PlanNode> nodes = new ArrayList<>(plan.nodes().size());
        for (PlanNode node : plan.nodes()) {
            if ("answer".equals(node.type()) && answerId.equals(node.id())) {
                Map<String, String> params = new LinkedHashMap<>(node.params());
                params.put("prompt", prompt);
                nodes.add(new PlanNode(node.id(), node.type(), params, node.displayName(), node.parentId()));
            } else {
                nodes.add(node);
            }
        }
        return new PlanJson(plan.planId(), plan.reason(), nodes, plan.edges(), plan.layout());
    }

    private String buildPrompt(PlanJson plan, List<String> upstreamIds) {
        String template = catalogTemplateOrEmpty();
        String upstream = buildUpstreamBlock(plan, upstreamIds);
        if (!StringUtils.hasText(template)) {
            return "";
        }
        if (template.contains(UPSTREAM_PLACEHOLDER)) {
            return template.replace(UPSTREAM_PLACEHOLDER, upstream);
        }
        return template.strip() + "\n\n上游数据：\n" + upstream;
    }

    private String catalogTemplateOrEmpty() {
        String fromCatalog = catalogHolder.snapshot().text("answer.template").map(String::strip).orElse("");
        if (StringUtils.hasText(fromCatalog)) {
            return fromCatalog;
        }
        log.warn("[PlanAnswerPromptAssembler] catalog missing id=answer.template");
        return "";
    }

    private static String buildUpstreamBlock(PlanJson plan, List<String> upstreamIds) {
        if (upstreamIds.isEmpty()) {
            return "（无上游节点）";
        }
        StringBuilder sb = new StringBuilder();
        Map<String, PlanNode> byId = plan.nodesById();
        for (String id : upstreamIds) {
            PlanNode node = byId.get(id);
            if (node == null) {
                continue;
            }
            String label = StringUtils.hasText(node.displayName()) ? node.displayName().strip() : id;
            sb.append("【").append(label).append("】\n");
            sb.append("{{").append(id).append(".output}}\n\n");
        }
        return sb.toString().strip();
    }

    private static String findAnswerNodeId(PlanJson plan, List<String> order) {
        if (plan.nodesById().containsKey(PlanNormalizer.ANSWER_NODE_ID)) {
            return PlanNormalizer.ANSWER_NODE_ID;
        }
        return null;
    }
}
