package com.sunshine.orchestrator.execution.loop;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.client.StreamToken;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LoopBodyFlushFoldTest {

    @Test
    void apply_foldsBodyStepOntoLoopWithIteration() {
        LoopBodyTimelineBridge bridge = new LoopBodyTimelineBridge(
                "loop-a1", "条件循环", List.of("agent-x"));
        AtomicInteger iter = new AtomicInteger(2);
        LoopBodyFlushFold fold = new LoopBodyFlushFold(bridge, iter);

        List<StreamToken> out = fold.apply(StreamToken.step(
                ProcessingStep.running("node-agent-x", "node", "框内分析")));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).step().id()).isEqualTo("node-loop-a1");
        assertThat(out.get(0).step().subSteps()).extracting(ProcessingStep::id)
                .containsExactly("i2-node-agent-x");
    }

    @Test
    void apply_passesThroughNonBodyToken() {
        LoopBodyTimelineBridge bridge = new LoopBodyTimelineBridge(
                "loop-a1", "条件循环", List.of("agent-x"));
        LoopBodyFlushFold fold = new LoopBodyFlushFold(bridge, new AtomicInteger(1));

        StreamToken answer = StreamToken.step(ProcessingStep.running("node-answer", "node", "回答"));
        assertThat(fold.apply(answer)).containsExactly(answer);
    }
}
