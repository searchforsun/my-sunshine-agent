package com.sunshine.tool.service;

import com.sunshine.tool.config.ToolTimelineProperties;
import com.sunshine.tool.dto.RagHitDto;
import com.sunshine.tool.dto.ToolSummarizeOutputRequest;
import com.sunshine.tool.registry.ToolRegistry;
import com.sunshine.tool.summary.RagHitSummarizer;
import com.sunshine.tool.summary.ToolOutputSummarizer;
import com.sunshine.tool.summary.ToolResultLabelService;
import com.sunshine.tool.tool.FinanceToolHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolSummarizeServiceTest {

    @Mock
    private FinanceToolHandler financeToolHandler;

    private ToolSummarizeService newService(ToolRegistry registry) {
        ToolResultLabelService labels = new ToolResultLabelService(new ToolTimelineProperties());
        ToolOutputSummarizer summarizer = new ToolOutputSummarizer(labels, new RagHitSummarizer(labels));
        return new ToolSummarizeService(registry, summarizer, labels);
    }

    @Test
    void summarizeOutput_resolvesKindFromCatalog() {
        when(financeToolHandler.name()).thenReturn("list_finance_messages");
        when(financeToolHandler.outputSummaryKind()).thenReturn("finance-list");
        ToolSummarizeService service = newService(new ToolRegistry(List.of(financeToolHandler)));
        var response = service.summarizeOutput(new ToolSummarizeOutputRequest(
                "list_finance_messages", null, "共 2 条"));
        assertThat(response.summary()).isEqualTo("2 条财务消息");
        assertThat(response.zeroHit()).isFalse();
    }

    @Test
    void summarizeRagHits_empty() {
        ToolSummarizeService service = newService(new ToolRegistry(List.of()));
        var response = service.summarizeRagHits(List.of());
        assertThat(response.summary()).isEqualTo("命中 0 条");
        assertThat(response.zeroHit()).isTrue();
    }

    @Test
    void summarizeRagHits_withDocs() {
        ToolSummarizeService service = newService(new ToolRegistry(List.of()));
        var response = service.summarizeRagHits(List.of(new RagHitDto("制度 A", "content")));
        assertThat(response.summary()).contains("制度 A");
        assertThat(response.zeroHit()).isFalse();
    }
}
