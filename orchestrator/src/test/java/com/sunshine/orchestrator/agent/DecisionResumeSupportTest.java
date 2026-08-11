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
                "conv-1", MSG, "user-1", "default", null, null, null, null, null));
    }

    @AfterEach
    void tearDown() {
        bridgeRegistry.clearAll();
        StepEventBridge.resetRegistry();
    }

    @Test
    void resume_reRegistersToken_withoutChangingQuestion() throws Exception {
        String oldToken = "old-token";
        List<DecisionOption> options = List.of(
                new DecisionOption("plan_a", "方案A", "稳妥", false),
                new DecisionOption("plan_b", "方案B", null, false));
        ProcessingStep awaiting = decisionStep("decision-" + oldToken, oldToken, "awaiting", null, options);
        String question = "选哪个方案？";
        String fingerprint = DecisionFingerprint.of(question, options);

        DecisionRegistry.Registration reg = new DecisionRegistry.Registration(
                "new-token", new CompletableFuture<>(), System.currentTimeMillis() + 300_000L);
        when(decisionRegistry.register(eq(MSG), eq("user-1"), eq(question), anyList(), eq(false)))
                .thenReturn(reg);
        when(decisionRegistry.awaitDecision(reg))
                .thenReturn(new DecisionResult("plan_a", null, System.currentTimeMillis()));

        DecisionResumeOutcome outcome = resumeSupport.prepareOnReactResume(MSG, BRIDGE, List.of(awaiting));

        ArgumentCaptor<List<DecisionOption>> optionsCaptor = ArgumentCaptor.forClass(List.class);
        verify(decisionRegistry).register(eq(MSG), eq("user-1"), eq(question), optionsCaptor.capture(), eq(false));
        assertThat(optionsCaptor.getValue()).isEqualTo(options);
        verify(timelineSupport).refreshAwaiting(
                eq(BRIDGE), eq(awaiting.id()), eq("new-token"), eq(question), anyList(), eq(false), any(Long.class));
        verify(timelineSupport).complete(
                eq(BRIDGE), eq("new-token"), any(DecisionResult.class), eq("方案A"));
        assertThat(outcome.shouldAbort()).isFalse();
        assertThat(StepEventBridge.consumeDecisionPreApproval(MSG, fingerprint)).isPresent()
                .get()
                .extracting(DecisionResult::choice)
                .isEqualTo("plan_a");
    }

    @Test
    void resume_resolved_injectsChoiceWithoutSecondToolCall() throws Exception {
        List<DecisionOption> options = List.of(
                new DecisionOption("plan_a", "方案A", "稳妥", false),
                new DecisionOption("plan_b", "方案B", null, false));
        ProcessingStep awaiting = decisionStep("decision-old", "old-token", "paused", null, options);
        DecisionRegistry.Registration reg = new DecisionRegistry.Registration(
                "new-token", new CompletableFuture<>(), System.currentTimeMillis() + 300_000L);
        when(decisionRegistry.register(eq(MSG), eq("user-1"), eq("选哪个方案？"), anyList(), eq(false)))
                .thenReturn(reg);
        when(decisionRegistry.awaitDecision(reg))
                .thenReturn(new DecisionResult("plan_b", "补充", System.currentTimeMillis()));

        DecisionResumeOutcome outcome = resumeSupport.prepareOnReactResume(MSG, BRIDGE, List.of(awaiting));

        // C1：不依赖二次 tool_call / consume；injectBlocks 必须带短格式
        assertThat(outcome.shouldAbort()).isFalse();
        assertThat(outcome.injectBlocks()).isNotEmpty();
        String block = String.join("\n", outcome.injectBlocks());
        assertThat(block).contains("【用户决策】");
        assertThat(block).contains("choice=plan_b");
        assertThat(block).contains("label=方案B");
        assertThat(block).contains("customInput=补充");
        assertThat(block).contains("选哪个方案？");
    }

    @Test
    void resume_timeout_abortsWithoutProceeding() throws Exception {
        List<DecisionOption> options = List.of(
                new DecisionOption("plan_a", "方案A", null, false),
                new DecisionOption("plan_b", "方案B", null, false));
        ProcessingStep awaiting = decisionStep("decision-old", "old-token", "awaiting", null, options);
        DecisionRegistry.Registration reg = new DecisionRegistry.Registration(
                "new-token", new CompletableFuture<>(), System.currentTimeMillis() + 300_000L);
        when(decisionRegistry.register(eq(MSG), eq("user-1"), eq("选哪个方案？"), anyList(), eq(false)))
                .thenReturn(reg);
        when(decisionRegistry.awaitDecision(reg))
                .thenReturn(new DecisionResult("__timeout__", null, System.currentTimeMillis()));

        DecisionResumeOutcome outcome = resumeSupport.prepareOnReactResume(MSG, BRIDGE, List.of(awaiting));

        assertThat(outcome.shouldAbort()).isTrue();
        assertThat(outcome.injectBlocks()).isEmpty();
        verify(timelineSupport).pause(eq(BRIDGE), eq("new-token"), any());
    }

    @Test
    void resume_cancelled_abortsWithoutProceeding() throws Exception {
        List<DecisionOption> options = List.of(
                new DecisionOption("plan_a", "方案A", null, false),
                new DecisionOption("plan_b", "方案B", null, false));
        ProcessingStep awaiting = decisionStep("decision-old", "old-token", "paused", null, options);
        DecisionRegistry.Registration reg = new DecisionRegistry.Registration(
                "new-token", new CompletableFuture<>(), System.currentTimeMillis() + 300_000L);
        when(decisionRegistry.register(eq(MSG), eq("user-1"), eq("选哪个方案？"), anyList(), eq(false)))
                .thenReturn(reg);
        when(decisionRegistry.awaitDecision(reg))
                .thenReturn(new DecisionResult("__cancelled__", null, System.currentTimeMillis()));

        DecisionResumeOutcome outcome = resumeSupport.prepareOnReactResume(MSG, BRIDGE, List.of(awaiting));

        assertThat(outcome.shouldAbort()).isTrue();
        verify(timelineSupport).pause(eq(BRIDGE), eq("new-token"), any());
    }

    @Test
    void resume_registerFailure_aborts() {
        List<DecisionOption> options = List.of(
                new DecisionOption("plan_a", "方案A", null, false),
                new DecisionOption("plan_b", "方案B", null, false));
        ProcessingStep awaiting = decisionStep("decision-old", "old-token", "awaiting", null, options);
        when(decisionRegistry.register(eq(MSG), eq("user-1"), eq("选哪个方案？"), anyList(), eq(false)))
                .thenThrow(new IllegalStateException("decision awaiting already exists"));

        DecisionResumeOutcome outcome = resumeSupport.prepareOnReactResume(MSG, BRIDGE, List.of(awaiting));

        assertThat(outcome.shouldAbort()).isTrue();
    }

    @Test
    void consumeDecisionPreApproval_skipsSecondBlock() {
        List<DecisionOption> options = List.of(
                new DecisionOption("plan_a", "方案A", "稳妥", false),
                new DecisionOption("plan_b", "方案B", null, false));
        String fingerprint = DecisionFingerprint.of("选哪个方案？", options);
        DecisionResult result = new DecisionResult("plan_b", "补充说明", System.currentTimeMillis());
        StepEventBridge.grantDecisionPreApproval(MSG, fingerprint, result);

        assertThat(StepEventBridge.consumeDecisionPreApproval(MSG, fingerprint)).contains(result);
        assertThat(StepEventBridge.consumeDecisionPreApproval(MSG, fingerprint)).isEmpty();
    }

    private static ProcessingStep decisionStep(
            String id, String token, String lifecycle, String choice, List<DecisionOption> options) {
        DecisionStepMeta decision = new DecisionStepMeta(
                token,
                "选哪个方案？",
                options,
                false,
                System.currentTimeMillis() + 60_000,
                choice,
                null);
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
