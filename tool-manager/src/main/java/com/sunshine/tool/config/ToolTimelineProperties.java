package com.sunshine.tool.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/** 工具时间线摘要模板 — SSOT：Nacos sunshine-tool-manager.yaml tool.timeline.result */
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

        private HitCountSummary hitCount = new HitCountSummary();
        private CountSummary financeList = new CountSummary();
        private FinanceSummary financeSummary = new FinanceSummary();
        private CountSummary oaTasks = defaultOaTasks();
        private String defaultEmpty = "无结果";
        private int truncateMaxChars = 80;

        private static CountSummary defaultOaTasks() {
            CountSummary cfg = new CountSummary();
            cfg.setZero("0 条 OA 待办");
            cfg.setWithCount("{count} 条 OA 待办");
            return cfg;
        }
    }

    @Getter
    @Setter
    public static class HitCountSummary {
        private String zero = "命中 0 条";
        private String withCount = "命中 {hitCount} 条";
        private String withSources = "命中 {hitCount} 条，来源：{sources}";
    }

    @Getter
    @Setter
    public static class CountSummary {
        private String zero = "0 条财务消息";
        private String withCount = "{count} 条财务消息";
    }

    @Getter
    @Setter
    public static class FinanceSummary {
        private String zero = "无汇总数据";
        private String withData = "{status} {count} 条，合计 ¥{amount}";
    }
}
