package com.sunshine.orchestrator.grounding;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.execution.WorkflowContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GroundingEvidenceSupportTest {

    @Test
    void fromWorkflowDetectsRagAndToolNodes() {
        WorkflowContext ctx = new WorkflowContext();
        ctx.putNode("rag", Map.of(
                "output", com.sunshine.orchestrator.execution.TypedValue.scalar("制度摘要"),
                "hitCount", com.sunshine.orchestrator.execution.TypedValue.scalar("2")));
        ctx.putNode("tool", Map.of(
                "output", com.sunshine.orchestrator.execution.TypedValue.scalar("[]"),
                "tool", com.sunshine.orchestrator.execution.TypedValue.scalar("sdk__sunshine-finance__list_my_expenses")));

        var evidence = GroundingEvidenceSupport.fromWorkflow(ctx);
        assertThat(evidence.hasToolOrRag()).isTrue();
        assertThat(evidence.sourceTexts()).anyMatch(text -> text.contains("制度摘要"));
    }

    @Test
    void fromSubAgentUsesInjectedBlocksAsEvidence() {
        var evidence = GroundingEvidenceSupport.fromSubAgent(
                List.of(),
                List.of(),
                List.of("上游制度摘要"));
        assertThat(evidence.hasToolOrRag()).isTrue();
    }

    @Test
    void fromTimelineDetectsCompletedToolStep() {
        ProcessingStep toolStep = ProcessingStep.done("tool-sdk__sunshine-finance__list_my_expenses", "tool", "财务查询", "3 条");
        var evidence = GroundingEvidenceSupport.fromTimeline(List.of(toolStep), null);
        assertThat(evidence.hasToolOrRag()).isTrue();
    }
}
