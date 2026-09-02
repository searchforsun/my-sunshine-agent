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
    void begin_emitsDecisionStepWithQuestionsAndNoAllowCustomInput() {
        String token = "tok-abc";
        String title = "需要确认";
        List<DecisionQuestion> questions = List.of(
                new DecisionQuestion(
                        "q1",
                        "您希望按哪种方式处理？请完整保留本问题原文。",
                        List.of(
                                new DecisionOption("plan_a", "方案A：快速处理"),
                                new DecisionOption("plan_b", "方案B：完整流程")),
                        false),
                new DecisionQuestion(
                        "q2",
                        "关注哪些方面？",
                        List.of(
                                new DecisionOption("perf", "性能"),
                                new DecisionOption("ux", "体验")),
                        true));
        long expiresAt = 1_753_721_880_000L;

        support.begin(BRIDGE, token, title, questions, expiresAt);

        ProcessingStep step = findDecisionStep(token);
        assertThat(step).isNotNull();
        assertThat(step.id()).isEqualTo("decision-" + token);
        assertThat(step.phase()).isEqualTo("decision");
        assertThat(step.lifecycle()).isEqualTo("awaiting");
        assertThat(step.label()).isEqualTo(title);
        assertThat(step.summary()).isNotNull();
        assertThat(step.summary().before()).isEqualTo(DecisionLabels.before());
        assertThat(step.summary().active()).isEqualTo(DecisionLabels.active(title));
        assertThat(step.summary().active()).contains(title);
        DecisionStepMeta decision = step.metadata().decision();
        assertThat(decision).isNotNull();
        assertThat(decision.token()).isEqualTo(token);
        assertThat(decision.title()).isEqualTo(title);
        assertThat(decision.questions()).containsExactlyElementsOf(questions);
        assertThat(decision.expiresAt()).isEqualTo(expiresAt);
        assertThat(decision.outcome()).isNull();
        assertThat(decision.answers()).isNull();
    }

    @Test
    void complete_marksDoneAndFillsOutcomeAnswers() {
        String token = "tok-done";
        String title = "选哪个？";
        List<DecisionQuestion> questions = List.of(
                new DecisionQuestion(
                        "q1",
                        "方案？",
                        List.of(
                                new DecisionOption("plan_a", "方案A"),
                                new DecisionOption("plan_b", "方案B")),
                        false));
        support.begin(BRIDGE, token, title, questions, 99L);
        flushed.clear();
        hookQueue.clear();

        DecisionAnswer answer = new DecisionAnswer("q1", List.of("plan_a"), "补充说明");
        DecisionResult result = new DecisionResult(
                "answered", title, List.of(answer), 1_700_000_000_000L);
        support.complete(BRIDGE, token, result);

        ProcessingStep step = findDecisionStep(token);
        assertThat(step).isNotNull();
        assertThat(step.lifecycle()).isEqualTo("done");
        String choiceText = DecisionLabels.formatChoiceFromAnswers(questions, result.answers());
        assertThat(choiceText).isEqualTo("方案A");
        assertThat(step.summary().after()).isEqualTo(DecisionLabels.after(choiceText));
        assertThat(step.endedAt()).isNotNull();
        DecisionStepMeta decision = step.metadata().decision();
        assertThat(decision.title()).isEqualTo(title);
        assertThat(decision.questions()).containsExactlyElementsOf(questions);
        assertThat(decision.outcome()).isEqualTo("answered");
        assertThat(decision.answers()).containsExactly(answer);
    }

    @Test
    void rebindAwaiting_keepsStepIdAndUpdatesToken() {
        String oldToken = "tok-old";
        String title = "需要确认";
        List<DecisionQuestion> questions = sampleQuestions();
        support.begin(BRIDGE, oldToken, title, questions, 1L);
        flushed.clear();
        hookQueue.clear();

        String newToken = "tok-new";
        long expiresAt = 2L;
        support.rebindAwaiting(BRIDGE, "decision-" + oldToken, newToken, title, questions, expiresAt);

        ProcessingStep step = findDecisionStepById("decision-" + oldToken);
        assertThat(step).isNotNull();
        assertThat(step.lifecycle()).isEqualTo("awaiting");
        assertThat(step.metadata().decision().token()).isEqualTo(newToken);
        assertThat(step.metadata().decision().questions()).containsExactlyElementsOf(questions);
        assertThat(step.metadata().decision().expiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void pause_marksPausedWithAfterText() {
        String token = "tok-pause";
        support.begin(BRIDGE, token, "Q?", sampleQuestions(), 1L);
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
        support.begin(BRIDGE, token, "Q?", sampleQuestions(), 1L);
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

    private static List<DecisionQuestion> sampleQuestions() {
        return List.of(
                new DecisionQuestion(
                        "q1",
                        "选？",
                        List.of(new DecisionOption("a", "A"), new DecisionOption("b", "B")),
                        false));
    }

    private ProcessingStep findDecisionStep(String token) {
        return findDecisionStepById("decision-" + token);
    }

    private ProcessingStep findDecisionStepById(String stepId) {
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
