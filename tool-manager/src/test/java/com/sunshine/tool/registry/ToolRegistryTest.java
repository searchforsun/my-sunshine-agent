package com.sunshine.tool.registry;

import com.sunshine.tool.tool.FinanceTool;
import com.sunshine.tool.tool.FinanceToolHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolRegistryTest {

    @Mock
    private FinanceTool financeTool;

    @InjectMocks
    private FinanceToolHandler financeToolHandler;

    @Test
    void invokesRegisteredHandler() {
        when(financeTool.listFinanceMessages(eq("pending"))).thenReturn("2 条待审批");

        ToolRegistry registry = new ToolRegistry(List.of(financeToolHandler));
        String result = registry.invoke("list_finance_messages", Map.of("status", "pending"));

        assertThat(result).isEqualTo("2 条待审批");
    }

    @Test
    void unknownToolThrows() {
        ToolRegistry registry = new ToolRegistry(List.of(financeToolHandler));

        assertThatThrownBy(() -> registry.invoke("unknown_tool", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown tool");
    }
}
