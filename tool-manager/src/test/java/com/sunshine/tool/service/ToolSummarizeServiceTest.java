package com.sunshine.tool.service;

import com.sunshine.tool.config.ToolTimelineProperties;
import com.sunshine.tool.dto.ToolSummarizeOutputRequest;
import com.sunshine.tool.entity.ToolDefinitionEntity;
import com.sunshine.tool.repo.ToolDefinitionRepository;
import com.sunshine.tool.summary.ToolResultLabelService;
import com.sunshine.tool.summary.ToolTimelineSummaryEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolSummarizeServiceTest {

    @Mock
    private ToolDefinitionRepository toolDefinitionRepository;

    private ToolSummarizeService newService() {
        ToolResultLabelService labels = new ToolResultLabelService(new ToolTimelineProperties());
        return new ToolSummarizeService(
                toolDefinitionRepository,
                new ToolTimelineSummaryEngine(),
                labels);
    }

    @Test
    void summarizeOutput_noTemplate_marksEmpty() {
        ToolDefinitionEntity entity = new ToolDefinitionEntity();
        entity.setId("mcp__demo-remote__search_docs");
        entity.setTimelineSummaryTemplate("");
        when(toolDefinitionRepository.findById("mcp__demo-remote__search_docs")).thenReturn(Optional.of(entity));

        ToolSummarizeService service = newService();
        var response = service.summarizeOutput(new ToolSummarizeOutputRequest(
                "mcp__demo-remote__search_docs", "raw"));
        assertThat(response.summary()).isEmpty();
        assertThat(response.empty()).isTrue();
    }

    @Test
    void summarizeOutput_withTemplate_resolvesSummary() {
        ToolDefinitionEntity entity = new ToolDefinitionEntity();
        entity.setId("sdk__sunshine-finance__summarize_my_expenses");
        entity.setTimelineSummaryTemplate("{status} {count} 条，合计 ¥{amount}");
        entity.setTimelineSummaryExtract(
                "{\"status\":\"regex:status=([^|\\\\s]+)\",\"count\":\"regex:count=(\\\\d+)\",\"amount\":\"regex:totalAmount=([\\\\d.]+)\"}");
        when(toolDefinitionRepository.findById("sdk__sunshine-finance__summarize_my_expenses"))
                .thenReturn(Optional.of(entity));

        ToolSummarizeService service = newService();
        var response = service.summarizeOutput(new ToolSummarizeOutputRequest(
                "sdk__sunshine-finance__summarize_my_expenses",
                "- status=pending | count=3 | totalAmount=124140.50"));
        assertThat(response.summary()).isEqualTo("pending 3 条，合计 ¥124140.50");
        assertThat(response.empty()).isFalse();
    }
}
