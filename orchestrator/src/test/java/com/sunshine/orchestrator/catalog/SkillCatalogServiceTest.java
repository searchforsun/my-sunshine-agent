package com.sunshine.orchestrator.catalog;

import com.sunshine.orchestrator.client.SkillCatalogClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
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
                new SkillCatalogIndexEntry("finance-analysis", "财务合规分析", "d", 1, true)));
        service.refresh();
        assertThat(service.indexEntries()).hasSize(1);
        assertThat(service.findIndex("finance-analysis")).isPresent();
    }

    @Test
    void find_fetchesDetailOnDemand() {
        when(catalogClient.fetchCatalogIndex()).thenReturn(List.of(
                new SkillCatalogIndexEntry("finance-analysis", "财务合规分析", "d", 1, true)));
        when(catalogClient.fetchSkillDetail("finance-analysis")).thenReturn(Optional.of(
                new SkillCatalogEntry("finance-analysis", "财务合规分析", "d", "overlay text", 1, true)));
        service.refresh();
        assertThat(service.find("finance-analysis")).isPresent();
        assertThat(service.overlayOrEmpty("finance-analysis")).isEqualTo("overlay text");
        verify(catalogClient).fetchSkillDetail("finance-analysis");
    }
}
