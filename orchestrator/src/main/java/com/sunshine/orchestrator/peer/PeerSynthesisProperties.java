package com.sunshine.orchestrator.peer;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "agent.peer")
public class PeerSynthesisProperties {
    private int maxRounds = 3;
    private String synthesisPrompt = """
            用户问题：{userQuery}

            上游数据：
            {transcript}

            请严格针对上述「用户问题」作答：仅依据上游数据回答用户所问。
            """;
}
