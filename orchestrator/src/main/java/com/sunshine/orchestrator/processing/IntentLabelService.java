package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.config.AgentPromptProperties;
import com.sunshine.orchestrator.prompt.TimelinePromptCatalog;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;

/**
 * 意图步骤 after 文案 — 主行统一状态文案（如「已完成意图识别」），模式/轨道/绑定细节由
 * routingTraces 在抽屉展示，不再按模式拼「将按…处理」类旧文案。
 * before / active 见 {@link TimelineStepLabelService}；think 见 {@link ThinkStepLabelService}。
 */
@Service
@RefreshScope
@RequiredArgsConstructor
public class IntentLabelService {

    private final TimelinePromptCatalog timelinePromptCatalog;

    @PostConstruct
    void init() {
        IntentLabels.bind(this);
    }

    /** intent 步 done 主行统一状态文案（Catalog timeline.intent.default-after） */
    public String intentAfterForPlan(String userQuery, ExecutionPlan plan) {
        return defaultAfter();
    }

    /** 重连 / 回放：与实时下发一致，统一状态文案（忽略 detail） */
    public String intentAfterSummary(String clippedQuery, String detail) {
        return defaultAfter();
    }

    private String defaultAfter() {
        AgentPromptProperties.IntentTimeline cfg = timelinePromptCatalog.intent();
        return TimelineLabelTemplates.textOrDefault(cfg.getDefaultAfter(), "已完成意图识别");
    }
}
