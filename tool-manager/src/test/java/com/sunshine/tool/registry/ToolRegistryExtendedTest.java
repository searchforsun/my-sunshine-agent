package com.sunshine.tool.registry;

import com.sunshine.tool.tool.FinanceDetailToolHandler;
import com.sunshine.tool.tool.FinanceSummaryToolHandler;
import com.sunshine.tool.tool.FinanceTool;
import com.sunshine.tool.tool.FinanceToolHandler;
import com.sunshine.tool.tool.OaTool;
import com.sunshine.tool.tool.OaToolHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolRegistryExtendedTest {

    @Mock
    private FinanceTool financeTool;
    @Mock
    private OaTool oaTool;

    @InjectMocks
    private FinanceToolHandler financeToolHandler;
    @InjectMocks
    private FinanceDetailToolHandler financeDetailToolHandler;
    @InjectMocks
    private FinanceSummaryToolHandler financeSummaryToolHandler;
    @InjectMocks
    private OaToolHandler oaToolHandler;

    @Test
    void invokesFinanceDetailHandler() {
        when(financeTool.getFinanceMessageDetail(eq("1001"))).thenReturn("detail-1001");
        ToolRegistry registry = new ToolRegistry(List.of(financeDetailToolHandler));
        assertThat(registry.invoke("get_finance_message_detail", Map.of("id", "1001")))
                .isEqualTo("detail-1001");
    }

    @Test
    void invokesFinanceSummaryHandler() {
        when(financeTool.summarizeFinanceByStatus(eq("pending"))).thenReturn("pending summary");
        ToolRegistry registry = new ToolRegistry(List.of(financeSummaryToolHandler));
        assertThat(registry.invoke("summarize_finance_by_status", Map.of("status", "pending")))
                .isEqualTo("pending summary");
    }

    @Test
    void invokesOaHandler() {
        when(oaTool.listOaTasks(eq("pending"))).thenReturn("3 oa tasks");
        ToolRegistry registry = new ToolRegistry(List.of(oaToolHandler));
        assertThat(registry.invoke("list_oa_tasks", Map.of("status", "pending")))
                .isEqualTo("3 oa tasks");
    }
}
