package com.sunshine.tool.controller;

import com.sunshine.common.core.result.R;
import com.sunshine.tool.dto.ToolSummarizeOutputRequest;
import com.sunshine.tool.dto.ToolSummarizeOutputResponse;
import com.sunshine.tool.service.ToolSummarizeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tools")
@RequiredArgsConstructor
public class ToolSummarizeController {

    private final ToolSummarizeService summarizeService;

    @PostMapping("/summarize-output")
    public R<ToolSummarizeOutputResponse> summarizeOutput(@RequestBody ToolSummarizeOutputRequest request) {
        return R.ok(summarizeService.summarizeOutput(request));
    }
}
