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
        ctx.putNode("rag", Map.of("output", "制度摘要", "hitCount", "2"));
        ctx.putNode("tool", Map.of("output", "[]", "tool", "list_finance_messages"));

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
        ProcessingStep toolStep = ProcessingStep.done("tool-list_finance_messages", "tool", "财务查询", "3 条");
        var evidence = GroundingEvidenceSupport.fromTimeline(List.of(toolStep), null);
        assertThat(evidence.hasToolOrRag()).isTrue();
    }
}
