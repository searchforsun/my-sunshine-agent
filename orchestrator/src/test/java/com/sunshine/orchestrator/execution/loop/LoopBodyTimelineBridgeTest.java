package com.sunshine.orchestrator.execution.loop;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.execution.agent.SubAgentTimelineBridge;
import com.sunshine.orchestrator.processing.TimelineLabelJUnitExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(TimelineLabelJUnitExtension.class)
class LoopBodyTimelineBridgeTest {

    @Test
    void wrap_foldsBodyOntoLoopSubStepsWithIterationPrefix() {
        LoopBodyTimelineBridge bridge = new LoopBodyTimelineBridge(
                "loop-a1", "条件循环", List.of("rag-body"));

        assertThat(bridge.isBodyToken(StreamToken.step(
                ProcessingStep.running("node-rag-body", "node", "循环内检索")))).isTrue();
        assertThat(bridge.isBodyToken(StreamToken.step(
                ProcessingStep.running("node-answer", "node", "回答")))).isFalse();

        List<StreamToken> round1 = bridge.wrap(
                StreamToken.step(ProcessingStep.done("node-rag-body", "node", "循环内检索", "hit-1")),
                1);
        assertThat(round1).hasSize(1);
        ProcessingStep loop = round1.get(0).step();
        assertThat(loop.id()).isEqualTo("node-loop-a1");
        assertThat(loop.subSteps()).extracting(ProcessingStep::id)
                .containsExactly("i1-node-rag-body");

        List<StreamToken> round2 = bridge.wrap(
                StreamToken.step(ProcessingStep.done("node-rag-body", "node", "循环内检索", "hit-2")),
                2);
        assertThat(round2.get(0).step().subSteps()).extracting(ProcessingStep::id)
                .containsExactly("i1-node-rag-body", "i2-node-rag-body");
    }

    @Test
    void wrap_agentNodeSnapshots_doNotDuplicateNestedReasoning() {
        // SubAgent 每步输出带累积 reasoning 的 node 快照；loop 折叠不得二次 append
        SubAgentTimelineBridge sub = new SubAgentTimelineBridge("agent-x", "框内分析");
        LoopBodyTimelineBridge loop = new LoopBodyTimelineBridge(
                "loop-a1", "条件循环", List.of("agent-x"));

        for (String delta : List.of("你", "好", "，世界")) {
            List<StreamToken> nodeTok = sub.wrap(StreamToken.stepDelta("think", "reasoning", delta));
            assertThat(nodeTok).hasSize(1);
            List<StreamToken> folded = loop.wrap(nodeTok.get(0), 1);
            assertThat(folded).hasSize(1);
        }

        ProcessingStep agent = loop.subSteps().stream()
                .filter(s -> "i1-node-agent-x".equals(s.id()))
                .findFirst()
                .orElseThrow();
        assertThat(agent.subSteps()).isNotEmpty();
        assertThat(agent.subSteps().get(0).reasoning()).isEqualTo("你好，世界");
    }

    @Test
    void wrap_completeWithoutSubSteps_preservesNestedAgentTimeline() {
        SubAgentTimelineBridge sub = new SubAgentTimelineBridge("agent-x", "框内分析");
        LoopBodyTimelineBridge loop = new LoopBodyTimelineBridge(
                "loop-a1", "条件循环", List.of("agent-x"));
        loop.wrap(sub.wrap(StreamToken.stepDelta("think", "reasoning", "先看凭证")).get(0), 1);
        // workflow finalize 的 complete 无嵌套 subSteps，不得抹掉已有 think/tool
        loop.wrap(StreamToken.step(ProcessingStep.done(
                "node-agent-x", "node", "框内分析", "已加载技能：finance-analysis\n终稿")), 1);

        ProcessingStep agent = loop.subSteps().stream()
                .filter(s -> "i1-node-agent-x".equals(s.id()))
                .findFirst()
                .orElseThrow();
        assertThat(agent.lifecycle()).isEqualTo("done");
        assertThat(agent.detail()).contains("终稿");
        assertThat(agent.subSteps()).isNotEmpty();
        assertThat(agent.subSteps().get(0).id()).isEqualTo("think");
        assertThat(agent.subSteps().get(0).reasoning()).isEqualTo("先看凭证");
    }
}
