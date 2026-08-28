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
        when(catalogClient.fetchCatalogIndex(null)).thenReturn(List.of(
                new SkillCatalogIndexEntry("finance-analysis", "财务合规分析", "d", 1, true, "none", "all", null, "default")));
        service.refresh();
        assertThat(service.indexEntries()).hasSize(1);
        assertThat(service.findIndex("finance-analysis")).isPresent();
    }

    @Test
    void find_fetchesDetailOnDemand() {
        when(catalogClient.fetchCatalogIndex(null)).thenReturn(List.of(
                new SkillCatalogIndexEntry("finance-analysis", "财务合规分析", "d", 1, true, "none", "all", null, "default")));
        when(catalogClient.fetchSkillDetail("finance-analysis")).thenReturn(Optional.of(
                new SkillCatalogEntry("finance-analysis", "财务合规分析", "d", "overlay text",
                        "[\"sdk__sunshine-biz__list_my_expenses\"]", 1, true, "none", null, "all", null, "default")));
        service.refresh();
        assertThat(service.find("finance-analysis")).isPresent();
        assertThat(service.overlayOrEmpty("finance-analysis")).isEqualTo("overlay text");
        assertThat(service.toolIds("finance-analysis"))
                .containsExactly("sdk__sunshine-biz__list_my_expenses");
        verify(catalogClient).fetchSkillDetail("finance-analysis");
    }

    @Test
    void renderForClassifier_includesSandboxCapability() {
        when(catalogClient.fetchCatalogIndex(null)).thenReturn(List.of(
                new SkillCatalogIndexEntry("sandbox-coding-demo", "沙箱编码演示", "docker 示例", 1, true, "docker", "all", null, "default")));
        service.refresh();
        assertThat(service.renderForClassifier("chat", "default"))
                .contains("sandbox-coding-demo")
                .contains("sandbox=docker");
    }

    @Test
    void sanitizeSkillPlan_stripsUnknownSkillId() {
        when(catalogClient.fetchCatalogIndex(null)).thenReturn(List.of());
        service.refresh();
        ExecutionPlan plan = new ExecutionPlan(
                ExecutionMode.FAST, null,
                Map.of(SkillBindingOutcome.PARAM_SKILL, "missing"), "llm");
        ExecutionPlan sanitized = service.sanitizeSkillPlan(plan, "default");
        assertThat(sanitized.params()).doesNotContainKey(SkillBindingOutcome.PARAM_SKILL);
    }

    @Test
    void sanitizeSkillPlan_stripsUnknownSkillIds_keepsKnown() {
        when(catalogClient.fetchCatalogIndex(null)).thenReturn(List.of(
                new SkillCatalogIndexEntry("finance-analysis", "财务合规分析", "d", 1, true, "none", "all", null, "default")));
        service.refresh();
        ExecutionPlan plan = new ExecutionPlan(
                ExecutionMode.FAST, null,
                Map.of("skillIds", "finance-analysis,missing-skill", SkillBindingOutcome.PARAM_SKILL, "finance-analysis"),
                "llm");
        ExecutionPlan sanitized = service.sanitizeSkillPlan(plan, "default");
        assertThat(sanitized.params().get("skillIds")).isEqualTo("finance-analysis");
    }

    @Test
    void discoverableForPrompt_filtersEnabledAndKind_excludesTriggered() {
        when(catalogClient.fetchCatalogIndex(null)).thenReturn(List.of(
                new SkillCatalogIndexEntry("finance-analysis", "财务合规分析", "财务规则", 1, true, "none", "all", null, "default"),
                new SkillCatalogIndexEntry("policy-qa", "制度问答", "制度查询", 1, true, "none", "chat", null, "default"),
                new SkillCatalogIndexEntry("coding-skill", "编码技能", "沙箱编码", 1, true, "none", "task", null, "default"),
                new SkillCatalogIndexEntry("disabled-skill", "禁用", "x", 1, false, "none", "all", null, "default")));
        service.refresh();

        List<SkillCatalogIndexEntry> chat = service.discoverableForPrompt("chat", List.of("finance-analysis"), "default");
        assertThat(chat.stream().map(SkillCatalogIndexEntry::id))
                .containsExactly("policy-qa");

        List<SkillCatalogIndexEntry> task = service.discoverableForPrompt("task", List.of(), "default");
        assertThat(task.stream().map(SkillCatalogIndexEntry::id))
                .containsExactlyInAnyOrder("finance-analysis", "coding-skill");
    }

    @Test
    void renderDiscoverableForPrompt_rendersNameAndDescription_respectsTopN() {
        when(catalogClient.fetchCatalogIndex(null)).thenReturn(List.of(
                new SkillCatalogIndexEntry("skill-a", "技能A", "用途A", 1, true, "none", "all", null, "default"),
                new SkillCatalogIndexEntry("skill-b", "技能B", "用途B", 1, true, "none", "all", null, "default"),
                new SkillCatalogIndexEntry("skill-c", "技能C", "用途C", 1, true, "none", "all", null, "default")));
        service.refresh();

        String rendered = service.renderDiscoverableForPrompt("chat", List.of(), 2, "default");
        assertThat(rendered)
                .contains("skill-a")
                .contains("技能A")
                .contains("用途A")
                .contains("还有 1 项");
        assertThat(rendered).doesNotContain("skill-c 技能C");
    }

    @Test
    void renderDiscoverableForPrompt_emptyReturnsBlank() {
        when(catalogClient.fetchCatalogIndex(null)).thenReturn(List.of());
        service.refresh();
        assertThat(service.renderDiscoverableForPrompt("chat", List.of(), 20, "default")).isEmpty();
    }

    @Test
    void discoverableForPrompt_filtersByTenant_defaultGlobalShared_privateIsolated() {
        when(catalogClient.fetchCatalogIndex(null)).thenReturn(List.of(
                new SkillCatalogIndexEntry("global-skill", "全局技能", "default 全局共享", 1, true, "none", "all", null, "default"),
                new SkillCatalogIndexEntry("tenant-a-skill", "租户A技能", "仅租户A", 1, true, "none", "all", null, "tenant-a"),
                new SkillCatalogIndexEntry("tenant-b-skill", "租户B技能", "仅租户B", 1, true, "none", "all", null, "tenant-b")));
        service.refresh();

        List<SkillCatalogIndexEntry> tenantA = service.discoverableForPrompt("chat", List.of(), "tenant-a");
        assertThat(tenantA.stream().map(SkillCatalogIndexEntry::id))
                .containsExactlyInAnyOrder("global-skill", "tenant-a-skill");
        assertThat(tenantA.stream().map(SkillCatalogIndexEntry::id))
                .doesNotContain("tenant-b-skill");
    }

    @Test
    void renderForClassifier_filtersByTenant() {
        when(catalogClient.fetchCatalogIndex(null)).thenReturn(List.of(
                new SkillCatalogIndexEntry("global-skill", "全局技能", "全局", 1, true, "none", "all", null, "default"),
                new SkillCatalogIndexEntry("tenant-b-skill", "租户B技能", "仅租户B", 1, true, "none", "all", null, "tenant-b")));
        service.refresh();
        assertThat(service.renderForClassifier("chat", "tenant-a"))
                .contains("global-skill")
                .doesNotContain("tenant-b-skill");
    }

    @Test
    void sanitizeSkillPlan_stripsTenantPrivateSkillForOtherTenant() {
        when(catalogClient.fetchCatalogIndex(null)).thenReturn(List.of(
                new SkillCatalogIndexEntry("tenant-a-skill", "租户A技能", "仅租户A", 1, true, "none", "all", null, "tenant-a")));
        service.refresh();
        ExecutionPlan plan = new ExecutionPlan(
                ExecutionMode.FAST, null,
                Map.of("skillIds", "tenant-a-skill", SkillBindingOutcome.PARAM_SKILL, "tenant-a-skill"), "llm");
        ExecutionPlan sanitized = service.sanitizeSkillPlan(plan, "tenant-b");
        assertThat(sanitized.params()).doesNotContainKey("skillIds");
        assertThat(sanitized.params()).doesNotContainKey(SkillBindingOutcome.PARAM_SKILL);
    }
}
