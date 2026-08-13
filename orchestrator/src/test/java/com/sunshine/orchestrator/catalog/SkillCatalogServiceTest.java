package com.sunshine.orchestrator.catalog;

import com.sunshine.orchestrator.client.SkillCatalogClient;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import com.sunshine.orchestrator.skill.SkillBindingOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillCatalogServiceTest {

    @Mock
    private SkillCatalogClient catalogClient;

    private SkillCatalogService service;

    @BeforeEach
    void setUp() {
        service = new SkillCatalogService(catalogClient);
    }

    @Test
    void refresh_loadsIndexOnly() {
        when(catalogClient.fetchCatalogIndex()).thenReturn(List.of(
                new SkillCatalogIndexEntry("finance-analysis", "财务合规分析", "d", 1, true, "none")));
        service.refresh();
        assertThat(service.indexEntries()).hasSize(1);
        assertThat(service.findIndex("finance-analysis")).isPresent();
    }

    @Test
    void find_fetchesDetailOnDemand() {
        when(catalogClient.fetchCatalogIndex()).thenReturn(List.of(
                new SkillCatalogIndexEntry("finance-analysis", "财务合规分析", "d", 1, true, "none")));
        when(catalogClient.fetchSkillDetail("finance-analysis")).thenReturn(Optional.of(
                new SkillCatalogEntry("finance-analysis", "财务合规分析", "d", "overlay text", 1, true, "none", null)));
        service.refresh();
        assertThat(service.find("finance-analysis")).isPresent();
        assertThat(service.overlayOrEmpty("finance-analysis")).isEqualTo("overlay text");
        verify(catalogClient).fetchSkillDetail("finance-analysis");
    }

    @Test
    void renderForClassifier_includesSandboxCapability() {
        when(catalogClient.fetchCatalogIndex()).thenReturn(List.of(
                new SkillCatalogIndexEntry("sandbox-coding-demo", "沙箱编码演示", "docker 示例", 1, true, "docker")));
        service.refresh();
        assertThat(service.renderForClassifier())
                .contains("sandbox-coding-demo")
                .contains("sandbox=docker");
    }

    @Test
    void sanitizeSkillPlan_stripsUnknownSkillId() {
        when(catalogClient.fetchCatalogIndex()).thenReturn(List.of());
        service.refresh();
        ExecutionPlan plan = new ExecutionPlan(
                ExecutionMode.FAST, null,
                Map.of(SkillBindingOutcome.PARAM_SKILL, "missing"), "llm");
        ExecutionPlan sanitized = service.sanitizeSkillPlan(plan);
        assertThat(sanitized.params()).doesNotContainKey(SkillBindingOutcome.PARAM_SKILL);
    }
}
