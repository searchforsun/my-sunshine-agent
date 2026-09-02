package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.processing.DecisionStepMeta;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.StepMetadata;
import com.sunshine.orchestrator.processing.StepSummary;
import com.sunshine.orchestrator.processing.TimelineLabelJUnitExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, TimelineLabelJUnitExtension.class})
class DecisionResumeSupportTest {

    private static final String MSG = "msg-resume-decision";
    private static final String BRIDGE = "main-bridge-resume";

    @Mock
    private DecisionRegistry decisionRegistry;
    @Mock
    private DecisionTimelineSupport timelineSupport;

    private DecisionResumeSupport resumeSupport;
    private StepEventBridgeRegistry bridgeRegistry;

    @BeforeEach
    void setUp() {
        resumeSupport = new DecisionResumeSupport(decisionRegistry, timelineSupport);
        bridgeRegistry = new StepEventBridgeRegistry();
        StepEventBridge.bindRegistry(bridgeRegistry);
        bridgeRegistry.bind(BRIDGE, new ProcessingTimelineSession(), new ConcurrentLinkedQueue<>());
        StepEventBridge.registerMainRun(MSG, BRIDGE);
        StepEventBridge.bindToolAudit(MSG, new StepEventBridge.ToolAuditContext(
                "conv-1", MSG, "user-1", "default", null, null, null, null, null, null));
    }

    @AfterEach
    void tearDown() {
        bridgeRegistry.clearAll();
        StepEventBridge.resetRegistry();
    }

    @Test
    void resume_reRegistersToken_withoutChangingQuestions() throws Exception {
        String oldToken = "old-token";
        List<DecisionQuestion> questions = sampleQuestions();
        String title = "选哪个方案？";
        ProcessingStep awaiting = decisionStep("decision-" + oldToken, oldToken, "awaiting", null, null, questions);
        String fingerprint = DecisionFingerprint.of(title, questions);

        DecisionRegistry.Registration reg = new DecisionRegistry.Registration(
                "new-token", new CompletableFuture<>(), System.currentTimeMillis() + 300_000L, title);
        when(decisionRegistry.register(eq(MSG), eq("user-1"), eq(title), anyList()))
                .thenReturn(reg);
        when(decisionRegistry.awaitDecision(reg))
                .thenReturn(new DecisionResult(
                        "answered",
                        title,
                        List.of(new DecisionAnswer("q1", List.of("plan_a"), null)),
                        System.currentTimeMillis()));

        DecisionResumeOutcome outcome = resumeSupport.prepareOnReactResume(MSG, BRIDGE, List.of(awaiting));

        ArgumentCaptor<List<DecisionQuestion>> questionsCaptor = ArgumentCaptor.forClass(List.class);
        verify(decisionRegistry).register(eq(MSG), eq("user-1"), eq(title), questionsCaptor.capture());
        assertThat(questionsCaptor.getValue()).isEqualTo(questions);
        verify(timelineSupport).rebindAwaiting(
                eq(BRIDGE), eq(awaiting.id()), eq("new-token"), eq(title), anyList(), any(Long.class));
        verify(timelineSupport).complete(eq(BRIDGE), eq("new-token"), any(DecisionResult.class));
        assertThat(outcome.shouldAbort()).isFalse();
        assertThat(StepEventBridge.consumeDecisionPreApproval(MSG, fingerprint)).isPresent()
                .get()
                .extracting(DecisionResult::outcome)
                .isEqualTo("answered");
    }

    @Test
    void resume_resolved_injectsAnswersWithoutSecondToolCall() throws Exception {
        List<DecisionQuestion> questions = sampleQuestions();
        String title = "选哪个方案？";
        ProcessingStep awaiting = decisionStep("decision-old", "old-token", "paused", null, null, questions);
        DecisionRegistry.Registration reg = new DecisionRegistry.Registration(
                "new-token", new CompletableFuture<>(), System.currentTimeMillis() + 300_000L, title);
        when(decisionRegistry.register(eq(MSG), eq("user-1"), eq(title), anyList()))
                .thenReturn(reg);
        when(decisionRegistry.awaitDecision(reg))
                .thenReturn(new DecisionResult(
                        "answered",
                        title,
                        List.of(new DecisionAnswer("q1", List.of(DecisionOption.CUSTOM_ID), "补充")),
                        System.currentTimeMillis()));

        DecisionResumeOutcome outcome = resumeSupport.prepareOnReactResume(MSG, BRIDGE, List.of(awaiting));

        assertThat(outcome.shouldAbort()).isFalse();
        assertThat(outcome.injectBlocks()).isNotEmpty();
        String block = String.join("\n", outcome.injectBlocks());
        assertThat(block).contains("【用户决策】");
        assertThat(block).contains("outcome=answered");
        assertThat(block).contains("title=" + title);
        assertThat(block).contains("choice=补充");
        assertThat(block).doesNotContain("q.q1");
        assertThat(block).contains(title);
    }

    @Test
    void resume_timeout_abortsWithoutProceeding() throws Exception {
        List<DecisionQuestion> questions = sampleQuestions();
        String title = "选哪个方案？";
        ProcessingStep awaiting = decisionStep("decision-old", "old-token", "awaiting", null, null, questions);
        DecisionRegistry.Registration reg = new DecisionRegistry.Registration(
                "new-token", new CompletableFuture<>(), System.currentTimeMillis() + 300_000L, title);
        when(decisionRegistry.register(eq(MSG), eq("user-1"), eq(title), anyList()))
                .thenReturn(reg);
        when(decisionRegistry.awaitDecision(reg))
                .thenReturn(new DecisionResult("timeout", title, List.of(), System.currentTimeMillis()));

        DecisionResumeOutcome outcome = resumeSupport.prepareOnReactResume(MSG, BRIDGE, List.of(awaiting));

        assertThat(outcome.shouldAbort()).isTrue();
        assertThat(outcome.injectBlocks()).isEmpty();
        verify(timelineSupport).pause(eq(BRIDGE), eq("new-token"), any());
    }

    @Test
    void resume_cancelled_abortsWithoutProceeding() throws Exception {
        List<DecisionQuestion> questions = sampleQuestions();
        String title = "选哪个方案？";
        ProcessingStep awaiting = decisionStep("decision-old", "old-token", "paused", null, null, questions);
        DecisionRegistry.Registration reg = new DecisionRegistry.Registration(
                "new-token", new CompletableFuture<>(), System.currentTimeMillis() + 300_000L, title);
        when(decisionRegistry.register(eq(MSG), eq("user-1"), eq(title), anyList()))
                .thenReturn(reg);
        when(decisionRegistry.awaitDecision(reg))
                .thenReturn(new DecisionResult("cancelled", title, List.of(), System.currentTimeMillis()));

        DecisionResumeOutcome outcome = resumeSupport.prepareOnReactResume(MSG, BRIDGE, List.of(awaiting));

        assertThat(outcome.shouldAbort()).isTrue();
        verify(timelineSupport).pause(eq(BRIDGE), eq("new-token"), any());
    }

    @Test
    void resume_registerFailure_aborts() {
        List<DecisionQuestion> questions = sampleQuestions();
        ProcessingStep awaiting = decisionStep(
                "decision-old", "old-token", "awaiting", null, null, questions);
        when(decisionRegistry.register(eq(MSG), eq("user-1"), eq("选哪个方案？"), anyList()))
                .thenThrow(new IllegalStateException("decision awaiting already exists"));

        DecisionResumeOutcome outcome = resumeSupport.prepareOnReactResume(MSG, BRIDGE, List.of(awaiting));

        assertThat(outcome.shouldAbort()).isTrue();
    }

    @Test
    void consumeDecisionPreApproval_skipsSecondBlock() {
        List<DecisionQuestion> questions = sampleQuestions();
        String title = "选哪个方案？";
        String fingerprint = DecisionFingerprint.of(title, questions);
        DecisionResult result = new DecisionResult(
                "answered",
                title,
                List.of(new DecisionAnswer("q1", List.of("plan_b"), "补充说明")),
                System.currentTimeMillis());
        StepEventBridge.grantDecisionPreApproval(MSG, fingerprint, result);

        assertThat(StepEventBridge.consumeDecisionPreApproval(MSG, fingerprint)).contains(result);
        assertThat(StepEventBridge.consumeDecisionPreApproval(MSG, fingerprint)).isEmpty();
    }

    private static List<DecisionQuestion> sampleQuestions() {
        return List.of(
                new DecisionQuestion(
                        "q1",
                        "选哪个方案？",
                        List.of(
                                new DecisionOption("plan_a", "方案A"),
                                new DecisionOption("plan_b", "方案B")),
                        false));
    }

    private static ProcessingStep decisionStep(
            String id,
            String token,
            String lifecycle,
            String outcome,
            List<DecisionAnswer> answers,
            List<DecisionQuestion> questions) {
        DecisionStepMeta decision = new DecisionStepMeta(
                token,
                "选哪个方案？",
                questions,
                System.currentTimeMillis() + 60_000,
                outcome,
                answers);
        return new ProcessingStep(
                id,
                "decision",
                lifecycle,
                new StepSummary(null, "等待决策", null),
                1L,
                null,
                null,
                null,
                null,
                null,
                null,
                2L,
                "选哪个方案？",
                StepMetadata.withDecision(null, decision),
                null,
                null,
                null);
    }
}
