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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillDiscoveryServiceTest {

    @Mock
    private SkillCatalogService skillCatalogService;

    private SkillDiscoveryService service;

    @BeforeEach
    void setUp() {
        service = new SkillDiscoveryService(skillCatalogService);
    }

    @Test
    void enrichKeepsValidSkillFromL3() {
        ExecutionPlan react = new ExecutionPlan(
                ExecutionMode.FAST, null,
                Map.of(SkillBindingOutcome.PARAM_SKILL, "finance-analysis"), "llm");
        when(skillCatalogService.sanitizeSkillPlan(react, "default")).thenReturn(react);

        ExecutionPlan enriched = service.enrich(react, "default");

        assertThat(enriched.params().get(SkillBindingOutcome.PARAM_SKILL)).isEqualTo("finance-analysis");
    }

    @Test
    void enrichStripsUnknownSkillViaCatalogSanitize() {
        ExecutionPlan react = new ExecutionPlan(
                ExecutionMode.FAST, null,
                Map.of(SkillBindingOutcome.PARAM_SKILL, "not-exists"), "llm");
        ExecutionPlan sanitized = new ExecutionPlan(ExecutionMode.FAST, null, Map.of(), "llm");
        when(skillCatalogService.sanitizeSkillPlan(react, "default")).thenReturn(sanitized);

        ExecutionPlan enriched = service.enrich(react, "default");

        assertThat(enriched.params()).doesNotContainKey(SkillBindingOutcome.PARAM_SKILL);
    }

    @Test
    void enrichSkipsNonReactMode() {
        ExecutionPlan workflow = new ExecutionPlan(ExecutionMode.WORKFLOW, "finance-smart", Map.of(), "rule");
        when(skillCatalogService.sanitizeSkillPlan(workflow, "default")).thenReturn(workflow);

        assertThat(service.enrich(workflow, "default")).isSameAs(workflow);
    }
}
