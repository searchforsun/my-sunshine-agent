package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.client.StreamToken;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * 子 Agent 正文 token 路由（Workflow {@code AgentStreamCollector} 与 spawn Bridge 共用）。
 * 分段/lifecycle → scope 父步；legacy plain content → {@code step_delta(result)}。
 */
public final class SubAgentContentTokens {

    private SubAgentContentTokens() {
    }

    /**
     * @return 已路由的 token；非 content 类返回 empty（由调用方继续 fold 步骤）
     */
    public static Optional<List<StreamToken>> route(StreamToken token, String parentStepId) {
        if (token == null || !StringUtils.hasText(parentStepId)) {
            return Optional.empty();
        }
        if (token.isContentLifecycle() || (token.isContent() && token.segmentId() != null)) {
            return Optional.of(List.of(token.withScopeNodeStepId(parentStepId)));
        }
        if (token.isContent() && token.text() != null) {
            return Optional.of(List.of(StreamToken.stepDelta(parentStepId, "result", token.text())));
        }
        return Optional.empty();
    }
}
