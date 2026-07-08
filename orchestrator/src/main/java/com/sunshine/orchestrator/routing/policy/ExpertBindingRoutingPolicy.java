package com.sunshine.orchestrator.routing.policy;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.orchestrator.exception.OrchestratorErrorCode;
import com.sunshine.orchestrator.expert.ExpertBindingOutcome;
import com.sunshine.orchestrator.expert.ExpertBindingParser;
import com.sunshine.orchestrator.expert.ExpertCollaborationParams;
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
    private final ExpertBindingParser expertBindingParser;

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
        ExpertBindingOutcome binding;
        try {
            binding = expertBindingParser.parse(message);
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
        params.put(ExpertCollaborationParams.EXPERT_IDS,
                binding.expertIds().stream().collect(Collectors.joining(",")));
        params.put(ExpertCollaborationParams.EFFECTIVE_QUERY, binding.effectiveQuery());
        return Mono.just(Optional.of(new ExecutionPlan(
                ExecutionMode.PEER_COLLAB, null, params, "expert:$mention")));
    }
}
