package com.sunshine.orchestrator.grounding;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.execution.WorkflowContext;
import com.sunshine.orchestrator.processing.ToolStepIds;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 从 Timeline / Workflow / 子 Agent 上下文组装 Grounding 证据 */
public final class GroundingEvidenceSupport {

    private GroundingEvidenceSupport() {
    }

    public static GroundingEvidence fromWorkflow(WorkflowContext ctx) {
        if (ctx == null) {
            return GroundingEvidence.none();
        }
        boolean hasToolOrRag = false;
        Set<String> texts = new LinkedHashSet<>();
        for (Map.Entry<String, Map<String, String>> entry : ctx.nodeEntries()) {
            Map<String, String> node = entry.getValue();
            if (node == null || node.isEmpty()) {
                continue;
            }
            if (node.containsKey("hitCount") || node.containsKey("tool")) {
                hasToolOrRag = true;
            }
            if (StringUtils.hasText(node.get("toolCalls"))) {
                hasToolOrRag = true;
            }
            collectText(node.get("output"), texts);
            collectText(node.get("detail"), texts);
            collectText(node.get("expandDetail"), texts);
            collectText(node.get("answer"), texts);
        }
        if (hasToolOrRag) {
            return GroundingEvidence.supported(List.copyOf(texts));
        }
        return new GroundingEvidence(false, List.copyOf(texts));
    }

    public static GroundingEvidence fromSubAgent(
            List<String> toolCalls,
            List<ProcessingStep> subSteps,
            List<String> injectedBlocks) {
        boolean hasTool = toolCalls != null && !toolCalls.isEmpty();
        boolean hasRag = hasCompletedRag(subSteps);
        boolean hasInjected = hasNonBlank(injectedBlocks);
        Set<String> texts = new LinkedHashSet<>();
        if (injectedBlocks != null) {
            injectedBlocks.forEach(text -> collectText(text, texts));
        }
        if (subSteps != null) {
            for (ProcessingStep step : subSteps) {
                if (step == null) {
                    continue;
                }
                collectText(step.detail(), texts);
                collectText(step.result(), texts);
                collectText(step.output(), texts);
            }
        }
        boolean supported = hasTool || hasRag || hasInjected;
        return supported ? GroundingEvidence.supported(List.copyOf(texts)) : GroundingEvidence.none();
    }

    public static GroundingEvidence fromTimeline(List<ProcessingStep> steps, String ragDetail) {
        boolean hasToolOrRag = StringUtils.hasText(ragDetail);
        Set<String> texts = new LinkedHashSet<>();
        collectText(ragDetail, texts);
        if (steps != null) {
            for (ProcessingStep step : steps) {
                if (step == null || step.id() == null) {
                    continue;
                }
                String id = step.id();
                if (ToolStepIds.isRagStep(id) || id.startsWith("tool-") || id.startsWith("tool@")) {
                    if ("done".equals(step.lifecycle())) {
                        hasToolOrRag = true;
                    }
                }
                collectText(step.detail(), texts);
                collectText(step.result(), texts);
                collectText(step.output(), texts);
            }
        }
        if (hasToolOrRag) {
            return GroundingEvidence.supported(List.copyOf(texts));
        }
        return new GroundingEvidence(false, List.copyOf(texts));
    }

    private static boolean hasCompletedRag(List<ProcessingStep> subSteps) {
        if (subSteps == null) {
            return false;
        }
        for (ProcessingStep step : subSteps) {
            if (step == null || step.id() == null) {
                continue;
            }
            if (ToolStepIds.isRagStep(step.id()) && "done".equals(step.lifecycle())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNonBlank(List<String> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return false;
        }
        for (String block : blocks) {
            if (StringUtils.hasText(block)) {
                return true;
            }
        }
        return false;
    }

    private static void collectText(String text, Set<String> target) {
        if (StringUtils.hasText(text)) {
            target.add(text.strip());
        }
    }
}
