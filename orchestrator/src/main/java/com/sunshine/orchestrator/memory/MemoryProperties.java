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
        private long maxToken = 256 * 1024;
        private double tokenRatio = 0.75;
        private int offloadSinglePreview = 200;
        /** 消息数触发阈值（0=禁用，仅作 token 触发的兜底） */
        private int msgThreshold = 0;
        private int lastKeep = 12;
        private int minConsecutiveToolMessages = 4;
        private double currentRoundCompressionRatio = 0.3;
        private int minCompressionTokenThreshold = 3000;

        // ── 方案 A：token 动态触发（CompactionConfig）────────────────────────
        /** 0=动态：effectiveTrigger = modelWindow - reserved */
        private int triggerTokens = 0;
        /** 摘要过程保留的 token 缓冲（仅 triggerTokens=0 时生效） */
        private int reserved = 20_000;
        /** -1=动态保留 tail：min(keepTokensMax, max(keepTokensMin, usable * ratio)) */
        private int keepTokens = -1;
        private int keepTokensMin = 2_000;
        private int keepTokensMax = 8_000;
        private double keepTokensRatio = 0.25;
        /** 压缩前是否做 LLM 记忆抽取（本工程 disableMemoryHooks，恒 false 省一次 LLM） */
        private boolean flushBeforeCompact = false;
        /** 压缩前原文落会话 JSONL（「压缩不可逆但原文可查」原则） */
        private boolean offloadBeforeCompact = true;

        // ── tail 裁剪（非 LLM，压缩触发前的常态操作）────────────────────────
        private boolean truncateArgsEnabled = true;
        /** 旧轮次大工具参数截断长度 */
        private int truncateArgsMaxChars = 2_000;
        private boolean pruneEnabled = true;
        /** 保护最近 N token 的工具结果不被裁剪 */
        private int pruneProtectTokens = 40_000;
        /** 可裁剪总量低于此值不触发裁剪 */
        private int pruneMinTokens = 20_000;
        /** 每个被裁剪工具结果保留 head+tail 预览的字符上限 */
        private int pruneMaxOutputChars = 2_000;
    }
}
