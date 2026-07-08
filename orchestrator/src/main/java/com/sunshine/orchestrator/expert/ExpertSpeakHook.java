package com.sunshine.orchestrator.expert;

import com.sunshine.orchestrator.agent.ManageTasksTool;
import com.sunshine.orchestrator.agent.StepEventBridge;
import com.sunshine.orchestrator.catalog.ToolCatalogService;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PreActingEvent;
import reactor.core.publisher.Mono;

/**
 * 专家 Hub 专用 Hook — 工具调用期间刷新 expert 步 active；
 * 正文在 {@link ExpertHubEngine} {@code agent.call()} 完成后一次性下发。
 */
public final class ExpertSpeakHook implements Hook {

    private final String bridgeId;
    private final ToolCatalogService toolCatalogService;

    ExpertSpeakHook(String bridgeId, ToolCatalogService toolCatalogService) {
        this.bridgeId = bridgeId;
        this.toolCatalogService = toolCatalogService;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreActingEvent pre) {
            String toolName = pre.getToolUse().getName();
            if (ManageTasksTool.NAME.equals(toolName)) {
                return Mono.just(event);
            }
            StepEventBridge.bindToolUseBridge(pre.getToolUse().getId(), bridgeId);
            String label = toolCatalogService.displayName(toolName);
            StepEventBridge.emitExpertSpeakToolActive(bridgeId, label);
            return Mono.just(event);
        }
        if (event instanceof PostActingEvent post) {
            StepEventBridge.unbindToolUseBridge(post.getToolUse().getId());
            return Mono.just(event);
        }
        return Mono.just(event);
    }
}
