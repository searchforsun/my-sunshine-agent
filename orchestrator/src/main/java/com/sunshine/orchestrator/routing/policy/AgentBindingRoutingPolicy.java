package com.sunshine.orchestrator.routing.policy;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.orchestrator.catalog.AgentBindingOutcome;
import com.sunshine.orchestrator.catalog.AgentBindingParser;
import com.sunshine.orchestrator.exception.OrchestratorErrorCode;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** L0：$ agent 硬绑定（仅轨 A；不改写 executionMode） */
@Component
@RequiredArgsConstructor
public class AgentBindingRoutingPolicy implements RoutingPolicy {
    private final AgentBindingParser agentBindingParser;

    @Override
    public int order() {
        return -10;
    }

    @Override
    public Mono<Optional<ExecutionPlan>> tryRoute(RoutingContext ctx) {
        if (ctx.isWorkflowTrack()) {
            return Mono.just(Optional.empty());
        }
        String message = ctx.userMessage();
        if (StringUtils.hasText(message) && message.strip().startsWith("#")) {
            return Mono.just(Optional.empty());
        }
        AgentBindingOutcome binding;
        try {
            binding = agentBindingParser.parse(message);
        } catch (IllegalStateException e) {
            return Mono.error(new BizException(OrchestratorErrorCode.AGENT_NOT_FOUND));
        }
        if (binding.unknown()) {
            return Mono.error(new BizException(OrchestratorErrorCode.AGENT_NOT_FOUND));
        }
        if (!binding.bound()) {
            return Mono.just(Optional.empty());
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("agentIds",
                binding.agentIds().stream().collect(Collectors.joining(",")));
        params.put("effectiveQuery", binding.effectiveQuery());
        ExecutionMode locked = ctx.effectiveLockedMode();
        return Mono.just(Optional.of(new ExecutionPlan(
                locked, null, params, "agent:$mention")));
    }
}
