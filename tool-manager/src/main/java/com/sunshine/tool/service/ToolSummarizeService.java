package com.sunshine.tool.service;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.tool.dto.RagHitDto;
import com.sunshine.tool.dto.ToolSummarizeOutputRequest;
import com.sunshine.tool.dto.ToolSummarizeOutputResponse;
import com.sunshine.tool.exception.ToolErrorCode;
import com.sunshine.tool.registry.ToolRegistry;
import com.sunshine.tool.summary.ToolOutputSummarizer;
import com.sunshine.tool.summary.ToolOutputSummaryKind;
import com.sunshine.tool.summary.ToolResultLabelService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ToolSummarizeService {

    private final ToolRegistry toolRegistry;
    private final ToolOutputSummarizer outputSummarizer;
    private final ToolResultLabelService labels;

    public ToolSummarizeOutputResponse summarizeOutput(ToolSummarizeOutputRequest request) {
        if (request == null) {
            throw new BizException(ToolErrorCode.SUMMARIZE_INPUT_REQUIRED);
        }
        String kind = resolveKind(request.toolName(), request.outputSummaryKind());
        String text = request.text();
        String summary = outputSummarizer.summarizeByKind(kind, text);
        boolean empty = text == null || text.isBlank() || labels.isEmptyToolSummary(summary);
        boolean zeroHit = ToolOutputSummaryKind.HIT_COUNT.id().equals(kind) && labels.isZeroHitSummary(summary);
        return new ToolSummarizeOutputResponse(summary, zeroHit, empty);
    }

    public ToolSummarizeOutputResponse summarizeRagHits(List<RagHitDto> hits) {
        if (hits == null || hits.isEmpty()) {
            String summary = labels.hitCountZero();
            return new ToolSummarizeOutputResponse(summary, true, true);
        }
        String docNames = hits.stream()
                .map(RagHitDto::docName)
                .filter(StringUtils::hasText)
                .distinct()
                .collect(Collectors.joining("、"));
        String summary = labels.hitCountWithSources(String.valueOf(hits.size()), docNames);
        return new ToolSummarizeOutputResponse(summary, false, false);
    }

    private String resolveKind(String toolName, String outputSummaryKind) {
        if (StringUtils.hasText(outputSummaryKind)) {
            return outputSummaryKind.strip();
        }
        if (StringUtils.hasText(toolName)) {
            return toolRegistry.outputSummaryKind(toolName.strip());
        }
        return ToolOutputSummaryKind.TRUNCATE.id();
    }
}
