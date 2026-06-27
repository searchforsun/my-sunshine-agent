package com.sunshine.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;

/** Plan 暂停/续跑配置 — Nacos agent.pause */
@Data
@RefreshScope
@ConfigurationProperties(prefix = "agent.pause")
public class AgentPauseProperties {
    /** HITL/Recovery 停止后续跑恢复同一交互（false 时退化为重跑节点） */
    private boolean resumeInteractionEnabled = true;
}
