package com.sunshine.orchestrator.routing;

import com.sunshine.orchestrator.catalog.AgentCatalogIndexEntry;
import com.sunshine.orchestrator.catalog.AgentCatalogService;
import com.sunshine.orchestrator.catalog.SkillCatalogIndexEntry;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * S-C 双阈值采纳（skill-sticky v3.8）：trigger 阈值 + δ + 可调度池 Top-K/可见性/剥离契约。
 * 候选中间档不落 Prompt（前缀稳定不变量）：未触发技能由模型经目录 + sunshine_search_skills 按需加载。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SkillAdoptionServiceTest {

    @Mock
    private SkillCatalogService skillCatalogService;
    @Mock
    private AgentCatalogService agentCatalogService;

    private AgentExecutionProperties properties;
    private SkillAdoptionService service;

    @BeforeEach
    void setUp() {
        properties = new AgentExecutionProperties();
        properties.getReact().getSkillAdoption().setEnabled(true);
        service = new SkillAdoptionService(properties, skillCatalogService, agentCatalogService);
        stubVisibleSkill("s1");
        stubVisibleSkill("s2");
        stubVisibleSkill("s3");
        stubVisibleSkill("s4");
        stubVisibleAgent("a1");
        stubVisibleAgent("a2");
        stubVisibleAgent("a3");
    }

    private void stubVisibleSkill(String id) {
        when(skillCatalogService.findIndex(id)).thenReturn(Optional.of(new SkillCatalogIndexEntry(
                id, id, "技能 " + id, 1, true, null, "all", null, "default")));
    }

    private void stubVisibleAgent(String id) {
        when(agentCatalogService.findIndex(id)).thenReturn(Optional.of(new AgentCatalogIndexEntry(
                id, id, "智能体 " + id, true, "all", null, null)));
    }

    private static ExecutionPlan plan(String skillScores, String agentScores) {
        return new ExecutionPlan(ExecutionMode.FAST, null,
                new java.util.HashMap<>(Map.of(
                        ExecutionPlan.PARAM_SKILL_SCORES, skillScores,
                        ExecutionPlan.PARAM_AGENT_SCORES, agentScores)),
                "l3");
    }

    @Test
    void highConfidenceWithDelta_triggersSingleSkillAndDropsScores() {
        ExecutionPlan result = service.apply(plan("s1=0.95,s2=0.3", ""), "default", "chat");
        assertThat(result.triggeredSkillIds()).containsExactly("s1");
        assertThat(result.params()).doesNotContainKey(ExecutionPlan.PARAM_SKILL_SCORES)
                .doesNotContainKey(ExecutionPlan.PARAM_AGENT_SCORES);
        assertThat(result.routingTraces()).extracting(RoutingTrace::layer).contains("L3");
    }

    @Test
    void highConfidenceWithoutDelta_doesNotTrigger() {
        // (0.9-0.85)/0.9 ≈ 0.056 < δ=0.2 → 不触发；未触发技能由模型经目录按需加载
        ExecutionPlan result = service.apply(plan("s1=0.9,s2=0.85", ""), "default", "chat");
        assertThat(result.triggeredSkillIds()).isEmpty();
        assertThat(result.params()).doesNotContainKey(ExecutionPlan.PARAM_SKILL_SCORES);
    }

    @Test
    void midConfidence_belowTrigger_notAdopted() {
        // s1 触发；s2/s3/s4 未过 trigger 且不落 Prompt 中间态，仅剥离分数
        ExecutionPlan result = service.apply(plan("s1=0.95,s2=0.7,s3=0.6,s4=0.55", "s9=0.2"),
                "default", "chat");
        assertThat(result.triggeredSkillIds()).containsExactly("s1");
        assertThat(result.params()).doesNotContainKey(ExecutionPlan.PARAM_SKILL_SCORES)
                .doesNotContainKey(ExecutionPlan.PARAM_AGENT_SCORES);
    }

    @Test
    void lowConfidenceBelowCandidate_excludedFromBoth() {
        ExecutionPlan result = service.apply(plan("s1=0.4", ""), "default", "chat");
        assertThat(result.triggeredSkillIds()).isEmpty();
        assertThat(result.params()).doesNotContainKey(ExecutionPlan.PARAM_SKILL_SCORES);
    }

    @Test
    void existingTriggeredSkill_notReplacedByAdoption() {
        ExecutionPlan inbound = plan("s2=0.7", "");
        inbound.params().put(ExecutionPlan.PARAM_SKILL_IDS, "s1");
        ExecutionPlan result = service.apply(inbound, "default", "chat");
        assertThat(result.triggeredSkillIds()).containsExactly("s1");
    }

    @Test
    void agentScores_buildSchedulablePoolDescending() {
        // a3=0.4 < candidate → 不入池；降序取 Top-K
        ExecutionPlan result = service.apply(plan("", "a3=0.4,a1=0.9,a2=0.6"), "default", "chat");
        assertThat(result.schedulableAgentIds()).containsExactly("a1", "a2");
        assertThat(result.params()).doesNotContainKey(ExecutionPlan.PARAM_AGENT_SCORES);
    }

    @Test
    void existingSchedulableAgents_notOverwrittenByAdoption() {
        ExecutionPlan inbound = plan("", "a1=0.9");
        inbound.params().put(ExecutionPlan.PARAM_AGENT_IDS, "sticky-agent");
        ExecutionPlan result = service.apply(inbound, "default", "chat");
        assertThat(result.schedulableAgentIds()).containsExactly("sticky-agent");
    }

    @Test
    void invisibleSkillScores_filteredBeforeAdoption() {
        when(skillCatalogService.findIndex("s1")).thenReturn(Optional.of(new SkillCatalogIndexEntry(
                "s1", "私有技能", "跨租户不可见", 1, true, null, "all", null, "acme")));
        ExecutionPlan result = service.apply(plan("s1=0.95", ""), "default", "chat");
        assertThat(result.triggeredSkillIds()).isEmpty();
        assertThat(result.params()).doesNotContainKey(ExecutionPlan.PARAM_SKILL_SCORES);
    }

    @Test
    void kindMismatchedSkill_excluded() {
        when(skillCatalogService.findIndex("s1")).thenReturn(Optional.of(new SkillCatalogIndexEntry(
                "s1", "任务技能", "仅 task 会话", 1, true, null, "task", null, "default")));
        ExecutionPlan result = service.apply(plan("s1=0.95", ""), "default", "chat");
        assertThat(result.triggeredSkillIds()).isEmpty();
    }

    @Test
    void adoptionDisabled_onlyStripsScores() {
        properties.getReact().getSkillAdoption().setEnabled(false);
        ExecutionPlan result = service.apply(plan("s1=0.95", "a1=0.9"), "default", "chat");
        assertThat(result.triggeredSkillIds()).isEmpty();
        assertThat(result.schedulableAgentIds()).isEmpty();
        assertThat(result.params()).doesNotContainKey(ExecutionPlan.PARAM_SKILL_SCORES)
                .doesNotContainKey(ExecutionPlan.PARAM_AGENT_SCORES);
    }

    @Test
    void workflowPlan_onlyStripsScores() {
        ExecutionPlan inbound = new ExecutionPlan(ExecutionMode.WORKFLOW, "wf",
                new java.util.HashMap<>(Map.of(
                        ExecutionPlan.PARAM_SKILL_SCORES, "s1=0.95",
                        ExecutionPlan.PARAM_AGENT_SCORES, "a1=0.9")),
                "轨B");
        ExecutionPlan result = service.apply(inbound, "default", "chat");
        assertThat(result.mode()).isEqualTo(ExecutionMode.WORKFLOW);
        assertThat(result.triggeredSkillIds()).isEmpty();
        assertThat(result.params()).doesNotContainKey(ExecutionPlan.PARAM_SKILL_SCORES);
    }
}
