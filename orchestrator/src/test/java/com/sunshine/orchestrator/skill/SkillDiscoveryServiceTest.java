package com.sunshine.orchestrator.skill;

import com.sunshine.orchestrator.catalog.SkillCatalogIndexEntry;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SkillDiscoveryServiceTest {

    @Mock
    private SkillCatalogService skillCatalogService;

    private SkillDiscoveryService service;

    private static final List<SkillCatalogIndexEntry> INDEX = List.of(
            new SkillCatalogIndexEntry("finance-analysis", "财务分析", "报销合规分析", 1, true),
            new SkillCatalogIndexEntry("policy-review", "制度审查", "制度对照", 1, true));

    @BeforeEach
    void setUp() {
        service = new SkillDiscoveryService(skillCatalogService);
        when(skillCatalogService.indexEntries()).thenReturn(INDEX);
    }

    @Test
    void discoverMatchesDescriptionKeywords() {
        Optional<String> skillId = service.tryDiscover("帮我做一笔报销的合规分析");
        assertThat(skillId).contains("finance-analysis");
    }

    @Test
    void discoverReturnsEmptyWhenNoMatch() {
        assertThat(service.tryDiscover("随便聊聊")).isEmpty();
    }

    @Test
    void enrichReactPlanWithDiscoveredSkill() {
        ExecutionPlan react = new ExecutionPlan(ExecutionMode.REACT, null, Map.of(), "llm");
        ExecutionPlan enriched = service.enrich(react, "帮我做一笔报销的合规分析");
        assertThat(enriched.mode()).isEqualTo(ExecutionMode.REACT);
        assertThat(enriched.params().get(SkillBindingOutcome.PARAM_SKILL)).isEqualTo("finance-analysis");
        assertThat(enriched.reason()).isEqualTo("skill:auto-discovered");
    }

    @Test
    void enrichSkipsWhenSkillAlreadyBound() {
        ExecutionPlan react = new ExecutionPlan(ExecutionMode.REACT, null,
                Map.of(SkillBindingOutcome.PARAM_SKILL, "policy-review"), "skill:@mention");
        ExecutionPlan enriched = service.enrich(react, "帮我做合规分析");
        assertThat(enriched).isSameAs(react);
    }

    @Test
    void enrichSkipsWhenUserForcedReact() {
        ExecutionPlan forced = new ExecutionPlan(ExecutionMode.REACT, null, Map.of(), "user:forced-react");
        ExecutionPlan enriched = service.enrich(forced, "待审批是否合规");
        assertThat(enriched.reason()).isEqualTo("user:forced-react");
        assertThat(enriched.params()).doesNotContainKey(SkillBindingOutcome.PARAM_SKILL);
    }

    @Test
    void enrichSkipsNonReactMode() {
        ExecutionPlan workflow = new ExecutionPlan(ExecutionMode.WORKFLOW, "finance-smart", Map.of(), "rule");
        assertThat(service.enrich(workflow, "合规分析")).isSameAs(workflow);
    }
}
