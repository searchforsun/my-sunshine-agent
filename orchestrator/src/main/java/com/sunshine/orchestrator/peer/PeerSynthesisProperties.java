package com.sunshine.orchestrator.peer;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/** 多专家 Hub 数值配置；提示词正文 SSOT = Catalog peer.* */
@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "agent.peer")
public class PeerSynthesisProperties {
    private int minRounds = 1;
    private int maxRounds = 3;
}
