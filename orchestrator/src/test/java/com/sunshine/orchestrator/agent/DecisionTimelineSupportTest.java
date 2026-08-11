package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.processing.DecisionLabels;
import com.sunshine.orchestrator.processing.DecisionStepMeta;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.TimelineLabelJUnitExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(TimelineLabelJUnitExtension.class)
class DecisionTimelineSupportTest {

    private static final String BRIDGE = "main-bridge";
    private static final String MSG = "msg-decision-1";

    private StepEventBridgeRegistry registry;
    private DecisionTimelineSupport support;
    private ConcurrentLinkedQueue<StreamToken> hookQueue;
    private List<StreamToken> flushed;

    @BeforeEach
    void setUp() {
        registry = new StepEventBridgeRegistry();
        StepEventBridge.bindRegistry(registry);
        support = new DecisionTimelineSupport();
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
    void begin_emitsDecisionStepWithAwaitingLifecycle() {
        String token = "tok-abc";
        String question = "您希望按哪种方式处理？请完整保留本问题原文。";
        List<DecisionOption> options = List.of(
                new DecisionOption("plan_a", "方案A：快速处理", "描述原文A", false),
                new DecisionOption("plan_b", "方案B：完整流程", "描述原文B", true));
        long expiresAt = 1_753_721_880_000L;

        support.begin(BRIDGE, token, question, options, false, expiresAt);

        ProcessingStep step = findDecisionStep(token);
        assertThat(step).isNotNull();
        assertThat(step.id()).isEqualTo("decision-" + token);
        assertThat(step.phase()).isEqualTo("decision");
        assertThat(step.lifecycle()).isEqualTo("awaiting");
        assertThat(step.summary()).isNotNull();
        assertThat(step.summary().before()).isEqualTo(DecisionLabels.before());
        assertThat(step.summary().active()).isEqualTo(DecisionLabels.active(question));
        assertThat(step.summary().active()).contains(question);
        DecisionStepMeta decision = step.metadata().decision();
        assertThat(decision).isNotNull();
        assertThat(decision.token()).isEqualTo(token);
        assertThat(decision.question()).isEqualTo(question);
        assertThat(decision.options()).containsExactlyElementsOf(options);
        assertThat(decision.allowCustomInput()).isFalse();
        assertThat(decision.expiresAt()).isEqualTo(expiresAt);
        assertThat(decision.choice()).isNull();
        assertThat(decision.customInput()).isNull();
    }

    @Test
    void complete_marksDoneAndFillsChoiceMetadata() {
        String token = "tok-done";
        String question = "选哪个？";
        List<DecisionOption> options = List.of(
                new DecisionOption("plan_a", "方案A", "快", false),
                new DecisionOption("plan_b", "方案B", "全", false));
        support.begin(BRIDGE, token, question, options, true, 99L);
        flushed.clear();
        hookQueue.clear();

        DecisionResult result = new DecisionResult("plan_a", "补充说明", 1_700_000_000_000L);
        support.complete(BRIDGE, token, result, "方案A");

        ProcessingStep step = findDecisionStep(token);
        assertThat(step).isNotNull();
        assertThat(step.lifecycle()).isEqualTo("done");
        assertThat(step.summary().after()).isEqualTo(DecisionLabels.after("方案A"));
        assertThat(step.endedAt()).isNotNull();
        DecisionStepMeta decision = step.metadata().decision();
        assertThat(decision.question()).isEqualTo(question);
        assertThat(decision.options()).containsExactlyElementsOf(options);
        assertThat(decision.choice()).isEqualTo("plan_a");
        assertThat(decision.customInput()).isEqualTo("补充说明");
        assertThat(decision.allowCustomInput()).isTrue();
    }

    @Test
    void pause_marksPausedWithAfterText() {
        String token = "tok-pause";
        support.begin(BRIDGE, token, "Q?", sampleOptions(), false, 1L);
        flushed.clear();
        hookQueue.clear();

        support.pause(BRIDGE, token, DecisionLabels.afterTimeout());

        ProcessingStep step = findDecisionStep(token);
        assertThat(step).isNotNull();
        assertThat(step.lifecycle()).isEqualTo("paused");
        assertThat(step.summary().after()).isEqualTo(DecisionLabels.afterTimeout());
        assertThat(step.endedAt()).isNotNull();
    }

    @Test
    void fail_marksErrorWithMessage() {
        String token = "tok-fail";
        support.begin(BRIDGE, token, "Q?", sampleOptions(), false, 1L);
        flushed.clear();
        hookQueue.clear();

        support.fail(BRIDGE, token, "内部异常");

        ProcessingStep step = findDecisionStep(token);
        assertThat(step).isNotNull();
        assertThat(step.lifecycle()).isEqualTo("error");
        assertThat(step.summary().after()).isEqualTo("内部异常");
        assertThat(step.result()).isEqualTo("内部异常");
        assertThat(step.endedAt()).isNotNull();
    }

    private static List<DecisionOption> sampleOptions() {
        return List.of(
                new DecisionOption("a", "A", null, false),
                new DecisionOption("b", "B", null, false));
    }

    private ProcessingStep findDecisionStep(String token) {
        String stepId = "decision-" + token;
        for (StreamToken streamToken : flushed) {
            if (streamToken.isStep() && streamToken.step() != null && stepId.equals(streamToken.step().id())) {
                return streamToken.step();
            }
        }
        for (StreamToken streamToken : hookQueue) {
            if (streamToken.isStep() && streamToken.step() != null && stepId.equals(streamToken.step().id())) {
                return streamToken.step();
            }
        }
        return null;
    }
}
