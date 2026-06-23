package com.sunshine.orchestrator.agent.runtime;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.plan.PlanTimeline;
import com.sunshine.orchestrator.plan.PlanJson;
import com.sunshine.orchestrator.plan.WorkflowPlanner;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.ProcessingTimelineSupport;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

/** Planner 专用运行时 — 产出 plan 步 Timeline（不含节点执行） */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlannerAgentRuntime implements AgentRuntime {

    private final WorkflowPlanner workflowPlanner;

    @Override
    public Flux<StreamToken> run(AgentRunRequest request) {
        ExecutionStreamContext ctx = new ExecutionStreamContext(
                null,
                request.assistantMessageId(),
                request.query(),
                request.memory(),
                null,
                null,
                null,
                request.userId(),
                request.tenantId(),
                new ExecutionPlan(ExecutionMode.PLAN_WORKFLOW, null, java.util.Map.of(), "planner"));
        return workflowPlanner.plan(ctx)
                .flatMapMany(this::planTokensOnly)
                .doOnError(e -> log.warn("[PlannerAgentRuntime] plan failed: {}", e.getMessage()));
    }

    private Flux<StreamToken> planTokensOnly(PlanJson planJson) {
        ProcessingTimelineSession session = ProcessingTimelineSupport.newSession();
        List<StreamToken> tokens = PlanTimeline.planStep(session, planJson);
        return Flux.fromIterable(tokens);
    }
}
