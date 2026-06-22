package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.config.AgentRewriteProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;

/** 启动时绑定 {@link RewriteTimelineLabels}，支持 Nacos 热刷新 */
@Service
@RefreshScope
@RequiredArgsConstructor
public class RewriteTimelineLabelService {

    private final AgentRewriteProperties rewriteProperties;

    @PostConstruct
    void init() {
        RewriteTimelineLabels.bind(rewriteProperties);
    }
}
