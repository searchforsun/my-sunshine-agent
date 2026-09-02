package com.sunshine.orchestrator.usage;

import com.sunshine.common.core.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 用量查询端点（phase5 5.2）：records=明细，summary=按 model 聚合。 */
@RestController
@RequestMapping("/api/usage")
@RequiredArgsConstructor
public class UsageController {

    private final LlmUsageService usageService;

    @GetMapping("/records")
    public R<?> records(
            @RequestParam(required = false) Long since,
            @RequestParam(required = false) Long until,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String tenantId) {
        return R.ok(usageService.search(since, until, model, tenantId));
    }

    @GetMapping("/summary")
    public R<?> summary(
            @RequestParam(required = false) Long since,
            @RequestParam(required = false) Long until,
            @RequestParam(required = false) String tenantId) {
        return R.ok(usageService.summary(since, until, tenantId));
    }

    @GetMapping("/daily")
    public R<?> daily(
            @RequestParam(required = false) Long since,
            @RequestParam(required = false) Long until,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String model) {
        return R.ok(usageService.daily(since, until, tenantId, model));
    }
}
