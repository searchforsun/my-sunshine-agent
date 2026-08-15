package com.sunshine.orchestrator.catalog;

import com.sunshine.orchestrator.client.AgentCatalogClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentCatalogServiceTest {

    @Mock
    private AgentCatalogClient catalogClient;

    @Mock
    private ToolCatalogService toolCatalogService;

    private AgentCatalogService service;

    @BeforeEach
    void setUp() {
        service = new AgentCatalogService(catalogClient, toolCatalogService);
    }

    @Test
    void renderForSpawnHint_appendsReadableToolList() {
        when(catalogClient.fetchCatalogIndex(null)).thenReturn(List.of(
                new AgentCatalogIndexEntry("compliance-agent", "业务合规对照智能体", "报销/假期合规对照",
                        true, "all", null,
                        "[\"sdk__sunshine-biz__list_my_expenses\",\"sdk__sunshine-biz__get_leave_balance\"]")));
        when(toolCatalogService.displayName(anyString())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            if ("sdk__sunshine-biz__list_my_expenses".equals(id)) {
                return "查询我的报销单";
            }
            if ("sdk__sunshine-biz__get_leave_balance".equals(id)) {
                return "查询假期余额";
            }
            return id;
        });
        service.refresh();

        String rendered = service.renderForSpawnHint(List.of("compliance-agent"));

        assertThat(rendered)
                .contains("- compliance-agent (业务合规对照智能体): 报销/假期合规对照")
                .contains("已装配工具：查询我的报销单、查询假期余额");
    }

    @Test
    void renderForSpawnHint_skipsUnknownAgentAndEmptyTools() {
        when(catalogClient.fetchCatalogIndex(null)).thenReturn(List.of(
                new AgentCatalogIndexEntry("policy-agent", "人事制度分析智能体", "制度解读",
                        true, "all", null, null)));
        service.refresh();

        String rendered = service.renderForSpawnHint(List.of("unknown-agent", "policy-agent"));

        assertThat(rendered)
                .contains("- policy-agent (人事制度分析智能体): 制度解读")
                .doesNotContain("已装配工具")
                .doesNotContain("unknown-agent");
    }
}
