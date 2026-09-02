package com.sunshine.llm.usage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "llm.usage")
public class LlmUsageProperties {

    private boolean enabled = true;
    private String topic = "llm-usage";
    /** 阶段一：请求未透传 tenant/user，归属维默认 default（链路透传后置 5.3） */
    private String tenantId = "default";

    /** 5.2.4 租户配额请求前校验（默认关，热切） */
    private Quota quota = new Quota();

    @Data
    public static class Quota {
        private boolean enabled = false;
        private String orchestratorBaseUrl = "http://sunshine-orchestrator";
        /** 校验结果本地缓存秒数（防每次请求打 orchestrator） */
        private long checkTtlSeconds = 30;
    }
}
