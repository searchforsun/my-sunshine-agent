package com.sunshine.orchestrator.catalog;

import com.sunshine.orchestrator.client.ToolManagerClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolSetResolverTest {

    @Mock
    private ToolManagerClient toolManagerClient;
    @Mock
    private ToolCatalogService toolCatalogService;
    @InjectMocks
    private ToolSetResolver resolver;

    @Test
    void resolveChatTools_intersectsEnabledPool() {
        when(toolManagerClient.fetchChatDefault("default")).thenReturn(List.of("A", "B", "D"));
        when(toolCatalogService.enabledIds("default")).thenReturn(Set.of("A", "B", "C"));

        assertThat(resolver.resolveChatTools("default")).containsExactly("A", "B");
    }

    @Test
    void intersectEnabledPool_filtersWhitelistByEnabledPool() {
        when(toolCatalogService.enabledIds("default")).thenReturn(Set.of("sdk__sunshine-finance__list_my_expenses", "search_knowledge"));

        assertThat(resolver.intersectEnabledPool(
                List.of("sdk__sunshine-finance__list_my_expenses", "ghost_tool"), "default"))
                .containsExactly("sdk__sunshine-finance__list_my_expenses");
    }

    @Test
    void resolveTaskTools_intersectsEnabledPool() {
        when(toolManagerClient.fetchTaskDefault("default")).thenReturn(List.of("A", "B", "D"));
        when(toolCatalogService.enabledIds("default")).thenReturn(Set.of("A", "B", "C"));

        assertThat(resolver.resolveTaskTools("default")).containsExactly("A", "B");
    }

    @Test
    void resolveTaskCriticalTools_intersectsEnabledPool() {
        when(toolManagerClient.fetchTaskCritical("default")).thenReturn(List.of("A", "B", "D"));
        when(toolCatalogService.enabledIds("default")).thenReturn(Set.of("A", "B", "C"));

        assertThat(resolver.resolveTaskCriticalTools("default")).containsExactly("A", "B");
    }

    @Test
    void resolveDefaultTools_taskKind_usesTaskSetNotChat() {
        when(toolManagerClient.fetchTaskDefault("default")).thenReturn(List.of("T1", "T2"));
        when(toolCatalogService.enabledIds("default")).thenReturn(Set.of("T1", "T2", "C1"));

        assertThat(resolver.resolveDefaultTools("default", "task")).containsExactly("T1", "T2");
        verify(toolManagerClient, never()).fetchChatDefault("default");
    }

    @Test
    void resolveDefaultTools_nullOrChatKind_usesChatSet() {
        when(toolManagerClient.fetchChatDefault("default")).thenReturn(List.of("C1"));
        when(toolCatalogService.enabledIds("default")).thenReturn(Set.of("C1"));

        assertThat(resolver.resolveDefaultTools("default", null)).containsExactly("C1");
        assertThat(resolver.resolveDefaultTools("default", "chat")).containsExactly("C1");
        verify(toolManagerClient, never()).fetchTaskDefault("default");
    }
}
