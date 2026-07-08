package com.sunshine.orchestrator.routing.policy;

import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import com.sunshine.orchestrator.routing.PeerPatternMatcher;
import com.sunshine.orchestrator.routing.StructuralPlanMatcher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;

/** L1b：peer 句式 → peer-collab（须在 multi-step structural 之后评估） */
@Component
@RequiredArgsConstructor
public class PeerStructuralRoutingPolicy implements RoutingPolicy {

    private final PeerPatternMatcher peerPatternMatcher;
    private final StructuralPlanMatcher structuralPlanMatcher;

    @Override
    public int order() {
        return 15;
    }

    @Override
    public Mono<Optional<ExecutionPlan>> tryRoute(RoutingContext ctx) {
        if (structuralPlanMatcher.looksLikeMultiStepPlan(ctx.userMessage())) {
            return Mono.just(Optional.empty());
        }
        if (!peerPatternMatcher.looksLikePeerCollab(ctx.userMessage())) {
            return Mono.just(Optional.empty());
        }
        return Mono.just(Optional.of(new ExecutionPlan(
                ExecutionMode.PEER_COLLAB,
                null,
                Map.of(),
                "structural:peer-collab")));
    }
}
