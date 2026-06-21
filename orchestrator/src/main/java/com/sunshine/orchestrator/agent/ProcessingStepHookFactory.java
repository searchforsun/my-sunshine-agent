package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.catalog.ToolCatalogService;
import io.agentscope.core.hook.Hook;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 按请求 bridgeId 创建 Hook，避免单例 Hook 在多并发 ReAct 会话间串线 */
@Component
@RequiredArgsConstructor
public class ProcessingStepHookFactory {

    private final ToolCatalogService toolCatalogService;

    public Hook forBridge(String bridgeId) {
        return new ProcessingStepHook(bridgeId, toolCatalogService);
    }
}
