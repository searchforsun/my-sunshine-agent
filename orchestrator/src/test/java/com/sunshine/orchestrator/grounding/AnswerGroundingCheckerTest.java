package com.sunshine.orchestrator.grounding;

import com.sunshine.orchestrator.config.AgentGroundingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerGroundingCheckerTest {

    private AnswerGroundingChecker checker;

    @BeforeEach
    void setUp() {
        AgentGroundingProperties properties = new AgentGroundingProperties();
        properties.setEnabled(true);
        properties.setBlockOnFailure(true);
        checker = new AnswerGroundingChecker(properties);
    }

    @Test
    void blocksAmountWithoutEvidence() {
        GroundingVerdict verdict = checker.check(
                "本月报销上限为 5000 元，请按制度执行。",
                GroundingEvidence.none());
        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.triggers()).contains("5000 元");
    }

    @Test
    void blocksPolicyNameWithoutEvidence() {
        GroundingVerdict verdict = checker.check(
                "依据《差旅管理制度》第三章执行。",
                GroundingEvidence.none());
        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.triggers()).contains("《差旅管理制度》");
    }

    @Test
    void passesWhenToolOrRagEvidencePresent() {
        GroundingVerdict verdict = checker.check(
                "本月报销上限为 5000 元。",
                GroundingEvidence.supported(List.of("报销上限 5000 元")));
        assertThat(verdict.passed()).isTrue();
    }

    @Test
    void passesForGenericAnswerWithoutEnterpriseClaims() {
        GroundingVerdict verdict = checker.check(
                "你好，我可以帮你查询制度或财务消息。",
                GroundingEvidence.none());
        assertThat(verdict.passed()).isTrue();
    }

    @Test
    void detectTriggersFindsAmountAndPolicy() {
        List<String> triggers = AnswerGroundingChecker.detectTriggers(
                "《费用管理办法》规定单笔 ¥1200 元以内可报销。");
        assertThat(triggers).contains("《费用管理办法》", "¥1200");
    }
}
