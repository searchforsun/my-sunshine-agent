package com.sunshine.orchestrator.execution.loop;

import com.sunshine.orchestrator.client.StreamToken;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent 子节点 Hook 经 {@code StepEventBridge} 直刷 Generation 时，
 * 将 body 步折叠进 loop.subSteps（与 Flux 路径 {@link LoopBodyTimelineBridge} 同语义）。
 */
public final class LoopBodyFlushFold {

    private final LoopBodyTimelineBridge bridge;
    private final AtomicInteger iteration;

    public LoopBodyFlushFold(LoopBodyTimelineBridge bridge, AtomicInteger iteration) {
        this.bridge = bridge;
        this.iteration = iteration;
    }

    public List<StreamToken> apply(StreamToken token) {
        if (token == null) {
            return List.of();
        }
        int iter = Math.max(1, iteration.get());
        if (bridge.isBodyToken(token)) {
            return bridge.wrap(token, iter);
        }
        // agent 分段正文 scope=node-{body} → i{n}-node-{body}，供 ContentBlock 挂到 loop.subSteps
        if (token.scopeNodeStepId() != null && bridge.isBodyScopedStepId(token.scopeNodeStepId())) {
            String scope = token.scopeNodeStepId();
            String rewritten = scope.startsWith("i" + iter + "-")
                    ? scope
                    : "i" + iter + "-" + scope;
            return List.of(token.withScopeNodeStepId(rewritten));
        }
        return List.of(token);
    }
}
