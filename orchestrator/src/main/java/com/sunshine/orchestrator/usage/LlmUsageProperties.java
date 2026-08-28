package com.sunshine.orchestrator.usage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/** 用量计量配置（phase5 5.2）：MQ 消费 + 日聚合 + 模型单价（成本估算）。 */
@Data
@ConfigurationProperties(prefix = "sunshine.llm-usage")
public class LlmUsageProperties {

    private boolean enabled = true;

    private String topic = "llm-usage";

    private String consumerGroup = "sunshine-llm-usage-consumer";

    /** 日聚合任务执行间隔（毫秒），默认 5 分钟 */
    private long aggregateIntervalMs = 300_000;

    /** 聚合重建回看窗口天数（默认 2：昨天 + 今天），覆盖延迟消费与跨天边界 */
    private int aggregateLookbackDays = 2;

    /** 模型单价（元 / 1M tokens），未配置的模型成本估算为 0 */
    private Map<String, ModelPrice> price = new LinkedHashMap<>();

    @Data
    public static class ModelPrice {
        private double inputPer1m;
        private double outputPer1m;
    }
}
