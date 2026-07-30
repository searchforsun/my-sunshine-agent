package com.sunshine.orchestrator.routing.policy;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.orchestrator.exception.OrchestratorErrorCode;
import com.sunshine.orchestrator.catalog.AgentBindingOutcome;
import com.sunshine.orchestrator.catalog.AgentBindingParser;
//  deleted (peer-collab removed)
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

/** L0：$ expert 硬绑定 → peer-collab */
@Component
@RequiredArgsConstructor
public class ExpertBindingRoutingPolicy implements RoutingPolicy {
    private final AgentBindingParser agentBindingParser;

    @Override
    public int order() {
        return -10;
    }

    @Override
    public Mono<Optional<ExecutionPlan>> tryRoute(RoutingContext ctx) {
        String message = ctx.userMessage();
        if (StringUtils.hasText(message) && message.strip().startsWith("#")) {
            return Mono.just(Optional.empty());
        }
        AgentBindingOutcome binding;
        try {
            binding = agentBindingParser.parse(message);
        } catch (IllegalStateException e) {
            return Mono.error(new BizException(OrchestratorErrorCode.EXPERT_NOT_FOUND));
        }
        if (binding.unknown()) {
            return Mono.error(new BizException(OrchestratorErrorCode.EXPERT_NOT_FOUND));
        }
        if (!binding.bound()) {
            return Mono.just(Optional.empty());
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put(.EXPERT_IDS,
                binding.expertIds().stream().collect(Collectors.joining(",")));
        params.put(.EFFECTIVE_QUERY, binding.effectiveQuery());
        return Mono.just(Optional.of(new ExecutionPlan(
                ExecutionMode.PEER_COLLAB, null, params, "expert:$mention")));
    }
}
