package com.sunshine.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/** HITL 写工具确认 — Nacos agent.hitl */
@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "agent.hitl")
public class AgentHitlProperties {

    private boolean enabled = true;
    /** 用户确认等待超时（秒） */
    private int timeoutSec = 120;
    /** 拒绝/超时后返回给模型的工具结果 */
    private String rejectionMessage = "用户未确认执行该写操作，已跳过。";
    /** ReAct 模式下注入 system 层：写工具须直接调用、由平台 HITL 确认 */
    private String agentPrompt = "";
    /** Plan-Workflow 节点失败时阻塞，等待用户重试/终止 */
    private boolean workflowNodeRecovery = true;
}
