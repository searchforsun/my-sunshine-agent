package com.sunshine.orchestrator.routing;

import com.sunshine.orchestrator.skill.SkillBindingOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** S-1 轻 sticky 合并：只粘触发，L0/L3 新触发整表替换，无触发继承 seed */
class RoutingStickyServiceTest {

    private RoutingStickyService service;

    @BeforeEach
    void setUp() {
        service = new RoutingStickyService();
    }

    @Test
    @DisplayName("无 seed / 空 seed 原样返回")
    void applySeed_noSeed_unchanged() {
        ExecutionPlan plan = new ExecutionPlan(ExecutionMode.FAST, null, Map.of(), "test");
        assertThat(service.applySeed(plan, null)).isSameAs(plan);
        assertThat(service.applySeed(plan, RoutingSeed.EMPTY)).isSameAs(plan);
    }

    @Test
    @DisplayName("无新触发时继承上轮 triggered skill 与可调度 agent，补 skill 单数")
    void applySeed_noTrigger_inheritsSeed() {
        ExecutionPlan plan = new ExecutionPlan(ExecutionMode.FAST, null, Map.of(), "test");
        RoutingSeed seed = new RoutingSeed(List.of("finance-analysis"), List.of("policy-agent"));

        ExecutionPlan merged = service.applySeed(plan, seed);

        assertThat(merged.triggeredSkillIds()).containsExactly("finance-analysis");
        assertThat(merged.schedulableAgentIds()).containsExactly("policy-agent");
        // 兼容单数契约：skill 首项补位
        assertThat(merged.params().get(SkillBindingOutcome.PARAM_SKILL)).isEqualTo("finance-analysis");
    }

    @Test
    @DisplayName("L0 /skill 整表替换 seed（不继承旧触发）")
    void applySeed_l0SkillReplacesSeed() {
        ExecutionPlan plan = new ExecutionPlan(ExecutionMode.FAST, null,
                Map.of("skillIds", "compliance-review", SkillBindingOutcome.PARAM_SKILL, "compliance-review"),
                "skill:/mention");
        RoutingSeed seed = new RoutingSeed(List.of("finance-analysis"), List.of("policy-agent"));

        ExecutionPlan merged = service.applySeed(plan, seed);

        assertThat(merged.triggeredSkillIds()).containsExactly("compliance-review");
        // agent 无本轮候选 → 仍继承
        assertThat(merged.schedulableAgentIds()).containsExactly("policy-agent");
    }

    @Test
    @DisplayName("L3 本轮新 agent 候选整表替换 seed")
    void applySeed_newAgentCandidatesReplaceSeed() {
        ExecutionPlan plan = new ExecutionPlan(ExecutionMode.FAST, null,
                Map.of(ExecutionPlan.PARAM_AGENT_IDS, "compliance-agent"),
                "l3");
        RoutingSeed seed = new RoutingSeed(List.of("finance-analysis"), List.of("policy-agent"));

        ExecutionPlan merged = service.applySeed(plan, seed);

        assertThat(merged.schedulableAgentIds()).containsExactly("compliance-agent");
        // skill 无本轮触发 → 继承 seed
        assertThat(merged.triggeredSkillIds()).containsExactly("finance-analysis");
    }

    @Test
    @DisplayName("Workflow 轨不做 skill sticky")
    void applySeed_workflowTrack_unchanged() {
        ExecutionPlan plan = new ExecutionPlan(ExecutionMode.WORKFLOW, "wf-1", Map.of(), "test");
        RoutingSeed seed = new RoutingSeed(List.of("finance-analysis"), List.of("policy-agent"));

        ExecutionPlan merged = service.applySeed(plan, seed);

        assertThat(merged).isSameAs(plan);
        assertThat(merged.triggeredSkillIds()).isEmpty();
    }

    @Test
    @DisplayName("skill 单数触发不再追加多值 skillIds（避免重复）")
    void applySeed_singleSkillTrigger_noSkillIdsDup() {
        ExecutionPlan plan = new ExecutionPlan(ExecutionMode.FAST, null,
                Map.of(SkillBindingOutcome.PARAM_SKILL, "finance-analysis"),
                "skill:/mention");
        RoutingSeed seed = new RoutingSeed(List.of("policy-qa"), List.of());

        ExecutionPlan merged = service.applySeed(plan, seed);

        assertThat(merged.triggeredSkillIds()).containsExactly("finance-analysis");
    }
}
