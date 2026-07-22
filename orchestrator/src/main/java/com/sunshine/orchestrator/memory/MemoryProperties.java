package com.sunshine.orchestrator.memory;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;

/**
 * 仅绑定 {@code agent.memory.auto-context}（单次 ReAct TOOL 压缩）。
 * 跨轮上下文见 {@code agent.context.*} / {@link com.sunshine.orchestrator.context.ContextProperties}。
 */
@Getter
@Setter
@RefreshScope
@ConfigurationProperties(prefix = "agent.memory")
public class MemoryProperties {

    /** ReAct 单次 run 内 TOOL 上下文压缩（AgentScope AutoContextMemory） */
    private AutoContext autoContext = new AutoContext();

    /** 4.6.4 — 单次 ReAct 内 AutoContextMemory 参数（相对库默认略收紧） */
    @Getter
    @Setter
    public static class AutoContext {
        private boolean enabled = true;
        /** 超长单条 TOOL 字符阈值，超出则 offload 预览 */
        private long largePayloadThreshold = 5 * 1024;
        private long maxToken = 128 * 1024;
        private double tokenRatio = 0.75;
        private int offloadSinglePreview = 200;
        private int msgThreshold = 40;
        private int lastKeep = 12;
        private int minConsecutiveToolMessages = 4;
        private double currentRoundCompressionRatio = 0.3;
        private int minCompressionTokenThreshold = 3000;
    }
}
