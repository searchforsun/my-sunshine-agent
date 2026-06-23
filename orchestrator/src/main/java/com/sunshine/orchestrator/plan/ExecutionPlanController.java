package com.sunshine.orchestrator.plan;

import com.sunshine.orchestrator.config.ReactiveBlocking;
import com.sunshine.orchestrator.plan.dto.ExecutionPlanDetailDto;
import com.sunshine.orchestrator.plan.dto.ExecutionPlanSummaryDto;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/** 动态 Plan 回放 API */
@RestController
@RequiredArgsConstructor
public class ExecutionPlanController {

    private final ExecutionPlanQueryService queryService;

    @GetMapping("/execution-plans/{planId}")
    public Mono<ExecutionPlanDetailDto> get(
            @PathVariable("planId") String planId,
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return ReactiveBlocking.call(() -> queryService.getDetail(planId, userId, tenantId));
    }

    @GetMapping("/execution-plans")
    public Mono<List<ExecutionPlanSummaryDto>> list(
            @RequestParam("conversationId") String conversationId,
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return ReactiveBlocking.call(() -> queryService.listByConversation(conversationId, userId, tenantId));
    }

    @GetMapping("/execution-plans/{planId}/nodes")
    public Mono<List<PlanNodeTrace>> nodes(
            @PathVariable("planId") String planId,
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return ReactiveBlocking.call(() -> queryService.listNodeTraces(planId, userId, tenantId));
    }
}
