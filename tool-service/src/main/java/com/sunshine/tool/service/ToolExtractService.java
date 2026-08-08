package com.sunshine.tool.service;

import com.sunshine.tool.dto.ToolExtractBindingsRequest;
import com.sunshine.tool.summary.ToolTimelineSummaryEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class ToolExtractService {

    private final ToolTimelineSummaryEngine timelineSummaryEngine;

    public Map<String, String> extractBindings(ToolExtractBindingsRequest request) {
        String extractJson = request != null ? request.extractJson() : null;
        String text = request != null ? request.text() : null;
        return timelineSummaryEngine.extractBindings(extractJson, text);
    }
}
