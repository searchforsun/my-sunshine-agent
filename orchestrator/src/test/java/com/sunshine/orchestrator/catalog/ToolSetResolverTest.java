package com.sunshine.orchestrator.catalog;

import com.sunshine.orchestrator.client.ToolSetClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolSetResolverTest {

    @Mock
    private ToolSetClient toolSetClient;
    @Mock
    private ToolCatalogService toolCatalogService;
    @InjectMocks
    private ToolSetResolver resolver;

    @Test
    void resolveReactTools_intersectsSetWithEnabledPool() {
        when(toolSetClient.fetchReactDefault("default")).thenReturn(List.of("A", "B", "D"));
        when(toolCatalogService.enabledIds("default")).thenReturn(Set.of("A", "B", "C"));

        assertThat(resolver.resolveReactTools("default")).containsExactly("A", "B");
    }

    @Test
    void intersectEnabledPool_filtersWhitelistByEnabledPool() {
        when(toolCatalogService.enabledIds("default")).thenReturn(Set.of("sdk__sunshine-finance__list_finance_messages", "search_knowledge"));

        assertThat(resolver.intersectEnabledPool(
                List.of("sdk__sunshine-finance__list_finance_messages", "ghost_tool"), "default"))
                .containsExactly("sdk__sunshine-finance__list_finance_messages");
    }

    @Test
    void resolvePlanWorkflowTools_intersectsSetWithEnabledPool() {
        when(toolSetClient.fetchPlanWorkflow("default")).thenReturn(List.of("A", "B", "D"));
        when(toolCatalogService.enabledIds("default")).thenReturn(Set.of("A", "B", "C"));

        assertThat(resolver.resolvePlanWorkflowTools("default")).containsExactly("A", "B");
    }

    @Test
    void resolvePlanWorkflowCriticalTools_intersectsSetWithEnabledPool() {
        when(toolSetClient.fetchPlanWorkflowCritical("default")).thenReturn(List.of("A", "B", "D"));
        when(toolCatalogService.enabledIds("default")).thenReturn(Set.of("A", "B", "C"));

        assertThat(resolver.resolvePlanWorkflowCriticalTools("default")).containsExactly("A", "B");
    }
}
