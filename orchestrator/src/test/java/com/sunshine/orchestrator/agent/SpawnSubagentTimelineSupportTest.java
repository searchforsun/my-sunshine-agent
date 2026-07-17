package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;

class SpawnSubagentTimelineSupportTest {

    private static final String BRIDGE = "main-bridge";
    private static final String MSG = "msg-spawn-1";

    private StepEventBridgeRegistry registry;
    private SpawnSubagentTimelineSupport support;
    private ConcurrentLinkedQueue<StreamToken> hookQueue;
    private List<StreamToken> flushed;

    @BeforeEach
    void setUp() {
        registry = new StepEventBridgeRegistry();
        StepEventBridge.bindRegistry(registry);
        support = new SpawnSubagentTimelineSupport();
        hookQueue = new ConcurrentLinkedQueue<>();
        flushed = new ArrayList<>();
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        registry.bind(BRIDGE, session, hookQueue);
        StepEventBridge.bindHitlBridge(BRIDGE, MSG, true);
        StepEventBridge.registerMainRun(MSG, BRIDGE);
        StepEventBridge.bindGenerationFlush(MSG, flushed::add);
    }

    @AfterEach
    void tearDown() {
        registry.clearAll();
        StepEventBridge.resetRegistry();
    }

    @Test
    void begin_emitsSubagentCardWithSpawnPrompt() {
        String runId = "run-abc";
        String prompt = "请检索制度并汇总要点";
        String label = "制度检索";

        support.begin(BRIDGE, runId, label, prompt);

        ProcessingStep step = findSubagentStep(runId);
        assertThat(step).isNotNull();
        assertThat(step.id()).isEqualTo("subagent-" + runId);
        assertThat(step.phase()).isEqualTo("subagent");
        assertThat(step.lifecycle()).isEqualTo("running");
        assertThat(step.label()).isEqualTo(label);
        assertThat(step.metadata()).isNotNull();
        assertThat(step.metadata().spawnPrompt()).isEqualTo(prompt);
        assertThat(step.summary()).isNotNull();
        assertThat(step.summary().active()).contains(label);
    }

    @Test
    void fold_wrapsSubStepsOntoParentCard() {
        String runId = "run-fold";
        String prompt = "子任务提示词";
        support.begin(BRIDGE, runId, "子任务", prompt);
        flushed.clear();
        hookQueue.clear();

        SpawnSubagentTimelineBridge bridge = new SpawnSubagentTimelineBridge(runId, "子任务", prompt);
        support.fold(BRIDGE, bridge, StreamToken.step(ProcessingStep.running("think", "think", "思考过程")));

        ProcessingStep parent = findSubagentStep(runId);
        assertThat(parent).isNotNull();
        assertThat(parent.subSteps()).isNotNull();
        assertThat(parent.subSteps()).extracting(ProcessingStep::id).containsExactly("think");
        assertThat(parent.metadata().spawnPrompt()).isEqualTo(prompt);
        assertThat(parent.lifecycle()).isEqualTo("running");
    }

    @Test
    void complete_marksParentDoneWithResult() {
        String runId = "run-done";
        String prompt = "完成提示";
        support.begin(BRIDGE, runId, "子任务", prompt);
        flushed.clear();
        hookQueue.clear();

        SpawnSubagentTimelineBridge bridge = new SpawnSubagentTimelineBridge(runId, "子任务", prompt);
        support.complete(BRIDGE, bridge, "最终产出文本");

        ProcessingStep parent = findSubagentStep(runId);
        assertThat(parent).isNotNull();
        assertThat(parent.lifecycle()).isEqualTo("done");
        assertThat(parent.result()).isEqualTo("最终产出文本");
        assertThat(parent.summary().after()).isEqualTo(SpawnSubagentLabels.after());
        assertThat(parent.metadata().spawnPrompt()).isEqualTo(prompt);
    }

    @Test
    void fail_marksParentError() {
        String runId = "run-fail";
        support.begin(BRIDGE, runId, "子任务", "p");
        flushed.clear();
        hookQueue.clear();

        SpawnSubagentTimelineBridge bridge = new SpawnSubagentTimelineBridge(runId, "子任务", "p");
        support.fail(BRIDGE, bridge, "超时失败");

        ProcessingStep parent = findSubagentStep(runId);
        assertThat(parent).isNotNull();
        assertThat(parent.lifecycle()).isEqualTo("error");
        assertThat(parent.result()).isEqualTo("超时失败");
        assertThat(parent.summary().after()).isEqualTo(SpawnSubagentLabels.afterFail());
    }

    private ProcessingStep findSubagentStep(String runId) {
        String stepId = "subagent-" + runId;
        for (StreamToken token : flushed) {
            if (token.isStep() && token.step() != null && stepId.equals(token.step().id())) {
                return token.step();
            }
        }
        for (StreamToken token : hookQueue) {
            if (token.isStep() && token.step() != null && stepId.equals(token.step().id())) {
                return token.step();
            }
        }
        return null;
    }
}
