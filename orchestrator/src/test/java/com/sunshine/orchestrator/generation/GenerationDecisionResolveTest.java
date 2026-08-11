package com.sunshine.orchestrator.generation;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.orchestrator.agent.DecisionRegistry;
import com.sunshine.orchestrator.exception.OrchestratorErrorCode;
import com.sunshine.orchestrator.execution.WorkflowPauseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class GenerationDecisionResolveTest {

    @Mock
    private WorkflowPauseService workflowPauseService;

    @Mock
    private DecisionRegistry decisionRegistry;

    private GenerationRegistry generationRegistry;

    @BeforeEach
    void setUp() {
        generationRegistry = new GenerationRegistry(workflowPauseService);
        ReflectionTestUtils.setField(generationRegistry, "decisionRegistry", decisionRegistry);
    }

    @Test
    @DisplayName("INPUT_REQUIRED → DECISION_INPUT_REQUIRED（400）")
    void resolve_mapsInputRequiredTo400() {
        assertThatThrownBy(() -> GenerationController.mapResolveOutcome(DecisionRegistry.ResolveOutcome.INPUT_REQUIRED))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> {
                    BizException biz = (BizException) ex;
                    assertThat(biz.getErrorCode()).isEqualTo(OrchestratorErrorCode.DECISION_INPUT_REQUIRED);
                    assertThat(biz.getErrorCode().getCode()).isEqualTo(400);
                    assertThat(biz.getErrorCode().getKey()).isEqualTo("decision_input_required");
                });
    }

    @Test
    @DisplayName("ACCEPTED → {accepted:true}")
    void resolve_acceptedReturnsTrue() {
        Map<String, Object> body = GenerationController.mapResolveOutcome(DecisionRegistry.ResolveOutcome.ACCEPTED);
        assertThat(body).containsEntry("accepted", true);
    }

    @Test
    @DisplayName("INVALID_CHOICE / EXPIRED / NOT_FOUND / FORBIDDEN 映射错误码")
    void resolve_mapsOtherOutcomesToErrorCodes() {
        assertThatThrownBy(() -> GenerationController.mapResolveOutcome(DecisionRegistry.ResolveOutcome.INVALID_CHOICE))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(OrchestratorErrorCode.DECISION_INVALID_CHOICE);

        assertThatThrownBy(() -> GenerationController.mapResolveOutcome(DecisionRegistry.ResolveOutcome.EXPIRED))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(OrchestratorErrorCode.DECISION_EXPIRED);

        assertThatThrownBy(() -> GenerationController.mapResolveOutcome(DecisionRegistry.ResolveOutcome.NOT_FOUND))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(OrchestratorErrorCode.DECISION_NOT_FOUND);

        assertThatThrownBy(() -> GenerationController.mapResolveOutcome(DecisionRegistry.ResolveOutcome.FORBIDDEN))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(OrchestratorErrorCode.DECISION_NOT_FOUND);
    }

    @Test
    @DisplayName("error key 与 spec 一致")
    void resolve_errorKeysMatchSpec() {
        assertThat(OrchestratorErrorCode.DECISION_INVALID_CHOICE.getKey()).isEqualTo("decision_invalid_choice");
        assertThat(OrchestratorErrorCode.DECISION_INPUT_REQUIRED.getKey()).isEqualTo("decision_input_required");
        assertThat(OrchestratorErrorCode.DECISION_EXPIRED.getKey()).isEqualTo("decision_expired");
        assertThat(OrchestratorErrorCode.DECISION_NOT_FOUND.getKey()).isEqualTo("decision_not_found");
    }

    @Test
    @DisplayName("releaseBlockingWaits 调用 decisionRegistry.cancelWaitersForMessage")
    void releaseBlockingWaits_cancelsDecisionWaiters() {
        generationRegistry.releaseBlockingWaitsForMessage("msg-decision-1");
        verify(decisionRegistry).cancelWaitersForMessage("msg-decision-1");
    }
}
