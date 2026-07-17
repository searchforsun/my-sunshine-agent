package com.sunshine.tool.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/** 工具时间线 — SSOT：Nacos sunshine-tool-manager.yaml tool.timeline */
@Getter
@Setter
@Component
@RefreshScope
@ConfigurationProperties(prefix = "tool.timeline")
public class ToolTimelineProperties {

    private ResultTimeline result = new ResultTimeline();

    @Getter
    @Setter
    public static class ResultTimeline {
        /** {@code {output}} 内置占位符首行截断上限 */
        private int truncateMaxChars = 80;
    }
}
