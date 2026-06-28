package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.ProcessingStepMerger;
import com.sunshine.orchestrator.client.StreamToken;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContentBlockAccumulatorTest {

    @Test
    void accumulatesMessageAndNodeBlocks() {
        ContentBlockAccumulator acc = new ContentBlockAccumulator();
        acc.onContentStart(StreamToken.contentStart("content-1", "think"));
        acc.onContent(StreamToken.contentInSegment("content-1", "ReAct 正文"));
        acc.onContentEnd(StreamToken.contentEnd("content-1"));

        acc.onContentStart(StreamToken.contentStart("content-2", "think-2")
                .withScopeNodeStepId("node-agent"));
        acc.onContent(StreamToken.contentInSegment("content-2", "子 Agent 正文")
                .withScopeNodeStepId("node-agent"));
        acc.onContentEnd(StreamToken.contentEnd("content-2").withScopeNodeStepId("node-agent"));

        List<ProcessingStep> steps = new ArrayList<>();
        steps.add(ProcessingStep.running("node-agent", "node", "子 Agent"));
        acc.mergeIntoSteps(steps);

        assertThat(acc.messageBlocksJson()).contains("ReAct 正文");
        assertThat(steps.get(0).contentBlocks()).hasSize(1);
        assertThat(steps.get(0).contentBlocks().get(0).text()).isEqualTo("子 Agent 正文");
    }
}
