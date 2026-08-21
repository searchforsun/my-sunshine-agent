package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.client.StreamToken;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * 子 Agent 正文 token 路由（Workflow {@code AgentStreamCollector} 与 spawn Bridge 共用）。
 * 分段/lifecycle → scope 父步；未分段 plain content → {@code step_delta(result)}。
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
        // 已归属其它父步（Worker 内 spawn 的子 agent 正文：scope=subagent-{runId}）：
        // 透传保持原 scope，由前端 updateNodeStepContent 递归挂到对应卡 contentBlocks；
        // 禁止重定向到当前父步——否则子 agent 正文并发流式全部混入 worker 抽屉正文。
        if (StringUtils.hasText(token.scopeNodeStepId())
                && !parentStepId.equals(token.scopeNodeStepId())) {
            return Optional.of(List.of(token));
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
