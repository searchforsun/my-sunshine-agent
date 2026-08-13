package com.sunshine.orchestrator.routing.policy;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.orchestrator.exception.OrchestratorErrorCode;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import com.sunshine.orchestrator.workflow.WorkflowBindingOutcome;
import com.sunshine.orchestrator.workflow.WorkflowBindingParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;

/** L0：# workflow 硬绑定（仅轨 B；轨 A 忽略并 warn） */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowBindingRoutingPolicy implements RoutingPolicy {
    private final WorkflowBindingParser workflowBindingParser;

    @Override
    public int order() {
        return -20;
    }

    @Override
    public Mono<Optional<ExecutionPlan>> tryRoute(RoutingContext ctx) {
        if (ctx.isAgentSkillTrack()) {
            WorkflowBindingOutcome peek = workflowBindingParser.resolve(ctx.userMessage(), ctx.forcedWorkflowId());
            if (peek.bound() || peek.unknown() || looksLikeHashMention(ctx.userMessage())) {
                log.warn("[WorkflowBinding] Track A ignores #workflow mention messageId={}",
                        ctx.traceMessageId());
            }
            return Mono.just(Optional.empty());
        }
        WorkflowBindingOutcome binding = workflowBindingParser.resolve(ctx.userMessage(), ctx.forcedWorkflowId());
        if (binding.unknown()) {
            return Mono.error(new BizException(OrchestratorErrorCode.WORKFLOW_NOT_FOUND));
        }
        if (!binding.bound()) {
            return Mono.just(Optional.empty());
        }
        String reason = StringUtils.hasText(ctx.forcedWorkflowId())
                ? "workflow:client"
                : "workflow:#mention";
        return Mono.just(Optional.of(new ExecutionPlan(
                ExecutionMode.WORKFLOW,
                binding.workflowId(),
                Map.of("effectiveQuery", binding.effectiveQuery()),
                reason)));
    }

    private static boolean looksLikeHashMention(String userMessage) {
        return StringUtils.hasText(userMessage) && userMessage.strip().startsWith("#");
    }
}
