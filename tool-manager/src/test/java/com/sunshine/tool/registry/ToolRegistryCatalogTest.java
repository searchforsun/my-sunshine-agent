package com.sunshine.tool.registry;

import com.sunshine.tool.dto.ToolCatalogEntry;
import com.sunshine.tool.tool.FinanceToolHandler;
import com.sunshine.tool.tool.OaToolHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ToolRegistryCatalogTest {

    @Mock
    private com.sunshine.tool.tool.FinanceTool financeTool;
    @Mock
    private com.sunshine.tool.tool.OaTool oaTool;

    @InjectMocks
    private FinanceToolHandler financeToolHandler;
    @InjectMocks
    private OaToolHandler oaToolHandler;

    @Test
    void listCatalog_returnsMetadata() {
        ToolRegistry registry = new ToolRegistry(List.of(financeToolHandler, oaToolHandler));
        List<ToolCatalogEntry> catalog = registry.listCatalog();

        assertThat(catalog).hasSize(2);
        assertThat(catalog).extracting(ToolCatalogEntry::id)
                .containsExactly("list_finance_messages", "list_oa_tasks");
        assertThat(catalog.get(0).displayName()).isEqualTo("查询待审批财务消息");
        assertThat(catalog.get(0).kind()).isEqualTo("remote");
        assertThat(catalog.get(0).timelinePhase()).isEqualTo("tool");
    }
}
