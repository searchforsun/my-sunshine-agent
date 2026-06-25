package com.sunshine.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/**
 * Grounding 校验配置（Task 3.7）— SSOT 见 Nacos agent.grounding
 */
@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "agent.grounding")
public class AgentGroundingProperties {

    /** 是否启用企业数据 grounding 校验 */
    private boolean enabled = true;
    /** 校验失败时是否阻断节点/标记 generate 失败 */
    private boolean blockOnFailure = true;
    /** 子 Agent / answer 节点失败时的用户可见说明 */
    private String rejectionMessage = "答复包含未经验证的企业数据表述，请先调用知识库或业务工具后再作答。";
}
