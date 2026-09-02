package com.sunshine.prompt.controller;

import com.sunshine.common.core.result.R;
import com.sunshine.prompt.dto.RoutingDryRunRequest;
import com.sunshine.prompt.dto.RoutingDryRunResponse;
import com.sunshine.prompt.dto.RoutingValidateRequest;
import com.sunshine.prompt.dto.RoutingValidateResponse;
import com.sunshine.prompt.service.PromptRoutingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/prompts/routing")
@RequiredArgsConstructor
public class PromptRoutingController {
    private final PromptRoutingService promptRoutingService;

    @PostMapping("/validate")
    public R<RoutingValidateResponse> validate(@RequestBody(required = false) RoutingValidateRequest request) {
        return R.ok(promptRoutingService.validate(request));
    }

    @PostMapping("/dry-run")
    public R<RoutingDryRunResponse> dryRun(@RequestBody RoutingDryRunRequest request) {
        return R.ok(promptRoutingService.dryRun(request));
    }
}
