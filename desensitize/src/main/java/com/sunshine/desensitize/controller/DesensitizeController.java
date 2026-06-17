package com.sunshine.desensitize.controller;

import com.sunshine.common.core.result.R;
import com.sunshine.desensitize.dto.TextScrubRequest;
import com.sunshine.desensitize.dto.TextScrubResponse;
import com.sunshine.desensitize.service.DesensitizeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/desensitize")
@RequiredArgsConstructor
public class DesensitizeController {

    private final DesensitizeService desensitizeService;

    @PostMapping("/scrub")
    public R<TextScrubResponse> scrub(@RequestBody TextScrubRequest request) {
        String scrubbed = desensitizeService.scrub(request != null ? request.text() : null);
        return R.ok(new TextScrubResponse(scrubbed));
    }
}
