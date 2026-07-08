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
            你是多专家协作召集助手。根据用户问题，从候选专家目录中选择 2~4 位最相关的专家，并估计讨论轮次上限。
            轮次含义：1=简单事实核对；2=需交叉验证；3=多观点分歧需多轮质疑。不得超过全局 maxRounds。
            只输出 JSON：{"expertIds":["id1","id2"],"maxRounds":2,"reason":"一句话说明"}，不要 markdown。
            """;
    private String complexityPrompt = """
            你是多专家协作轮次评估助手。用户已显式指定专家名单，请根据问题复杂度估计 Hub 讨论轮次上限（整数）。
            1=简单事实核对；2=需交叉验证；3=多观点分歧需多轮质疑。不得超过用户消息中的全局上限。
            只输出 JSON：{"maxRounds":1,"reason":"一句话说明"}，不要 markdown。
            """;
}
