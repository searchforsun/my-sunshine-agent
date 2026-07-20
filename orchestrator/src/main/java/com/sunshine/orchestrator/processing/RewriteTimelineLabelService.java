package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.prompt.TimelinePromptCatalog;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;

/** 启动时绑定 {@link RewriteTimelineLabels}，Catalog 热更新后随 Snapshot 生效 */
@Service
@RefreshScope
@RequiredArgsConstructor
public class RewriteTimelineLabelService {

    private final TimelinePromptCatalog timelinePromptCatalog;

    @PostConstruct
    void init() {
        RewriteTimelineLabels.bind(timelinePromptCatalog);
    }
}
