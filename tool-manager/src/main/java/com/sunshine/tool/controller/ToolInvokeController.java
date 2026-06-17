package com.sunshine.tool.controller;

import com.sunshine.common.core.result.R;
import com.sunshine.tool.dto.ToolInvokeRequest;
import com.sunshine.tool.service.ToolInvokeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tools")
@RequiredArgsConstructor
public class ToolInvokeController {

    private final ToolInvokeService toolInvokeService;

    @PostMapping("/invoke")
    public R<String> invoke(@RequestBody ToolInvokeRequest request) {
        return R.ok(toolInvokeService.invoke(request.name(), request.params()));
    }
}
