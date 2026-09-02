package com.sunshine.bff.controller;

import com.sunshine.bff.client.OrchestratorClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/** BFF 透传 orchestrator /api/usage/*（用量记录/聚合/配额，供 /ops 用量页）。 */
@RestController
@RequestMapping("/api/usage")
@RequiredArgsConstructor
public class UsageController {

    private final OrchestratorClient orchestratorClient;

    @GetMapping("/records")
    public Mono<Object> records(
            @RequestParam(required = false) Long since,
            @RequestParam(required = false) Long until,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String tenantId) {
        return orchestratorClient.usageRecords(since, until, model, tenantId);
    }

    @GetMapping("/summary")
    public Mono<Object> summary(
            @RequestParam(required = false) Long since,
            @RequestParam(required = false) Long until,
            @RequestParam(required = false) String tenantId) {
        return orchestratorClient.usageSummary(since, until, tenantId);
    }

    @GetMapping("/daily")
    public Mono<Object> daily(
            @RequestParam(required = false) Long since,
            @RequestParam(required = false) Long until,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String model) {
        return orchestratorClient.usageDaily(since, until, tenantId, model);
    }

    @GetMapping("/quota")
    public Mono<Object> quotaList() {
        return orchestratorClient.usageQuotaList();
    }

    @GetMapping("/quota/check")
    public Mono<Object> quotaCheck(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String model) {
        return orchestratorClient.usageQuotaCheck(tenantId, model);
    }

    @PostMapping("/quota")
    public Mono<Object> quotaUpsert(@RequestBody Map<String, Object> body) {
        return orchestratorClient.usageQuotaUpsert(body);
    }

    @DeleteMapping("/quota/{tenantId}")
    public Mono<Object> quotaDelete(@PathVariable String tenantId) {
        return orchestratorClient.usageQuotaDelete(tenantId);
    }
}
