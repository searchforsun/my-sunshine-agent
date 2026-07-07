package com.sunshine.orchestrator.execution.handler;

import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.execution.NodeResult;
import com.sunshine.orchestrator.execution.agent.AgentNodeDetailSummarizer;
import com.sunshine.orchestrator.execution.agent.AgentNodeOutput;
import com.sunshine.orchestrator.execution.agent.AgentStreamCollector;
import com.sunshine.orchestrator.grounding.AnswerGroundingChecker;
import com.sunshine.orchestrator.grounding.GroundingEvidenceSupport;
import com.sunshine.orchestrator.grounding.GroundingVerdict;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 子 Agent 流收集器 → NodeResult（含 Grounding） */
@Slf4j
final class AgentNodeResultBuilder {

    private AgentNodeResultBuilder() {
    }

    static NodeResult build(
            AgentStreamCollector collector,
            String skillId,
            AnswerGroundingChecker groundingChecker,
            SkillCatalogService skillCatalogService) {
        String answer = collector.content();
        List<String> injected = collector.auditRequest() != null
                ? collector.auditRequest().injectedBlocks() : List.of();
        GroundingVerdict verdict = groundingChecker.check(
                answer,
                GroundingEvidenceSupport.fromSubAgent(
                        collector.toolCalls(), collector.subSteps(), injected));
        if (!verdict.passed()) {
            log.warn("[AgentNodeHandler] 子 Agent Grounding 未通过: {}", verdict.reason());
            return NodeResult.fail(verdict.reason());
        }
        String reasoning = collector.subSteps().stream()
                .filter(s -> s.id() != null && s.id().startsWith("think"))
                .map(s -> s.reasoning() != null ? s.reasoning() : "")
                .collect(Collectors.joining());
        List<String> toolCalls = collector.toolCalls();
        String summaryLine = AgentNodeDetailSummarizer.summarize(answer, reasoning, toolCalls.size());
        String expandDetail = AgentNodeDetailSummarizer.expandDetail(resolveSkillLabel(skillId, skillCatalogService), answer);
        AgentNodeOutput output = new AgentNodeOutput(answer, toolCalls);
        Map<String, String> outputs = new LinkedHashMap<>();
        outputs.put("answer", output.answer());
        outputs.put("output", output.answer());
        outputs.put("toolCalls", String.join(",", output.toolCalls()));
        outputs.put("detail", summaryLine);
        outputs.put("expandDetail", expandDetail);
        return NodeResult.ok(outputs);
    }

    private static String resolveSkillLabel(String skillId, SkillCatalogService skillCatalogService) {
        if (!StringUtils.hasText(skillId)) {
            return null;
        }
        return skillCatalogService.find(skillId.strip())
                .map(entry -> StringUtils.hasText(entry.displayName()) ? entry.displayName().strip() : skillId)
                .orElse(skillId.strip());
    }
}
