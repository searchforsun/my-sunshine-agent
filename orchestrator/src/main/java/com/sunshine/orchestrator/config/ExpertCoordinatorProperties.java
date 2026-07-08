package com.sunshine.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "agent.expert")
public class ExpertCoordinatorProperties {
    private String coordinatorPrompt = """
            你是多专家协作召集助手。根据用户问题，从候选专家目录中选择 2~4 位最相关的专家。
            只输出 JSON：{"expertIds":["id1","id2"],"reason":"一句话说明"}，不要 markdown。
            """;
}
