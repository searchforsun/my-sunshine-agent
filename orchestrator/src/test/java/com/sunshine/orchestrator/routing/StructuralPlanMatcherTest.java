package com.sunshine.orchestrator.routing;

import com.sunshine.orchestrator.config.RoutingRuleProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StructuralPlanMatcherTest {

    private StructuralPlanMatcher matcher;

    @BeforeEach
    void setUp() {
        RoutingRuleProperties props = new RoutingRuleProperties();
        matcher = new StructuralPlanMatcher(props);
    }

    @Test
    void detectsCrossDomainMultiStepQuery() {
        assertThat(matcher.looksLikeMultiStepPlan(
                "先检索差旅报销相关制度，再查询待审批报销单，并对每条做合规分析后给出结论"))
                .isTrue();
    }

    @Test
    void rejectsSingleStepFinanceList() {
        assertThat(matcher.looksLikeMultiStepPlan("有哪些待审批报销")).isFalse();
    }

    @Test
    void rejectsSingleStepCompliance() {
        assertThat(matcher.looksLikeMultiStepPlan("待审批报销是否合规")).isFalse();
    }

    @Test
    void disabledStructuralReturnsFalse() {
        RoutingRuleProperties props = new RoutingRuleProperties();
        props.getStructural().setEnabled(false);
        StructuralPlanMatcher off = new StructuralPlanMatcher(props);
        assertThat(off.looksLikeMultiStepPlan(
                "先检索差旅报销相关制度，再查询待审批报销单，并对每条做合规分析后给出结论"))
                .isFalse();
    }

    @Test
    void customPatternFromConfig() {
        RoutingRuleProperties props = new RoutingRuleProperties();
        props.getStructural().setMultiStepPatterns(List.of("串联处理"));
        props.getStructural().setDomainGroups(Map.of(
                "a", List.of("制度"),
                "b", List.of("报销")));
        StructuralPlanMatcher custom = new StructuralPlanMatcher(props);
        assertThat(custom.looksLikeMultiStepPlan("串联处理制度与报销")).isTrue();
    }

    @Test
    void multiStepThenKeywordWithoutConfiguredDomain_fallsThrough() {
        RoutingRuleProperties props = new RoutingRuleProperties();
        props.getStructural().setDomainGroups(Map.of("finance", List.of("报销")));
        StructuralPlanMatcher matcher = new StructuralPlanMatcher(props);
        assertThat(matcher.looksLikeMultiStepPlan("先查邮件再查报销")).isFalse();
    }
}
