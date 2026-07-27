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
 * 动态 Plan 的 answer 节点 prompt - Catalog {@code answer.template}。
 * Planner 在 answer prompt 中显式写 {@code {{nodeId.output}}} 引用，不再自动注入全量上游，
 * 避免 meta 指令进入 reasoning。
 * 缺 Catalog -> 空串 + warn（禁止 Java 模板兜底）。
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
        String prompt = buildPrompt();
        List<PlanNode> nodes = new ArrayList<>(plan.nodes().size());
        for (PlanNode node : plan.nodes()) {
            if ("answer".equals(node.type()) && answerId.equals(node.id())) {
                Map<String, Object> params = new LinkedHashMap<>(node.params());
                params.put("prompt", prompt);
                nodes.add(new PlanNode(node.id(), node.type(), params, node.inputs(), node.displayName(), node.parentId()));
            } else {
                nodes.add(node);
            }
        }
        return new PlanJson(plan.planId(), plan.reason(), nodes, plan.edges(), plan.layout());
    }

    private String buildPrompt() {
        String template = catalogTemplateOrEmpty();
        if (!StringUtils.hasText(template)) {
            return "";
        }
        // Planner 在 answer prompt 中显式写 {{nodeId.output}} 引用，不再自动注入全量上游
        if (template.contains(UPSTREAM_PLACEHOLDER)) {
            return template.replace(UPSTREAM_PLACEHOLDER, "");
        }
        return template.strip();
    }

    private String catalogTemplateOrEmpty() {
        String fromCatalog = catalogHolder.snapshot().text("answer.template").map(String::strip).orElse("");
        if (StringUtils.hasText(fromCatalog)) {
            return fromCatalog;
        }
        log.warn("[PlanAnswerPromptAssembler] catalog missing id=answer.template");
        return "";
    }

    private static String findAnswerNodeId(PlanJson plan, List<String> order) {
        if (plan.nodesById().containsKey(PlanNormalizer.ANSWER_NODE_ID)) {
            return PlanNormalizer.ANSWER_NODE_ID;
        }
        return null;
    }
}
