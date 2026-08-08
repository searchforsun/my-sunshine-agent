package com.sunshine.tool.service;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.tool.dto.ToolSummarizeOutputRequest;
import com.sunshine.tool.dto.ToolSummarizeOutputResponse;
import com.sunshine.tool.entity.ToolDefinitionEntity;
import com.sunshine.tool.exception.ToolErrorCode;
import com.sunshine.tool.repo.ToolDefinitionRepository;
import com.sunshine.tool.summary.ToolResultLabelService;
import com.sunshine.tool.summary.ToolTimelineSummaryEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ToolSummarizeService {

    private final ToolDefinitionRepository toolDefinitionRepository;
    private final ToolTimelineSummaryEngine timelineSummaryEngine;
    private final ToolResultLabelService labels;

    public ToolSummarizeOutputResponse summarizeOutput(ToolSummarizeOutputRequest request) {
        if (request == null || !StringUtils.hasText(request.toolName())) {
            throw new BizException(ToolErrorCode.SUMMARIZE_INPUT_REQUIRED);
        }
        ToolDefinitionEntity entity = toolDefinitionRepository.findById(request.toolName().strip()).orElse(null);
        String template = entity != null ? entity.getTimelineSummaryTemplate() : "";
        String extract = entity != null ? entity.getTimelineSummaryExtract() : null;
        String summary = timelineSummaryEngine.resolve(
                template, extract, request.text(), labels.truncateMaxChars());
        boolean empty = !StringUtils.hasText(template) || labels.isEmptyToolSummary(summary);
        return new ToolSummarizeOutputResponse(summary, false, empty);
    }
}
