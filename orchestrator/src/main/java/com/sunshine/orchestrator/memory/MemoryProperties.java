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
        /** 压缩摘要模板（含 {messages} 占位）；空串用 AgentScope 默认 */
        private String summaryPrompt = DEFAULT_SUMMARY_PROMPT;

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

    /**
     * 压缩摘要默认模板：保留各轮思考要点（默认 AgentScope 模板丢弃 ThinkingBlock，
     * 导致压缩后模型失去「先思考再行动」样例 → 后段思考退化）。
     */
    public static final String DEFAULT_SUMMARY_PROMPT =
            """
            在下面的对话历史中，每轮 AI 消息可能同时包含「思考内容」（reasoning/thinking）与正文。思考内容是行动依据，必须保留。

            请提取继续完成用户目标所需的最重要上下文，覆盖以下章节（没有则写 None）：

            ## SESSION INTENT
            用户当前的核心目标或请求。

            ## SUMMARY
            最重要的上下文、决策、推理依据与已排除的选项。**必须包含各轮思考要点**（如「已决定先检索 X 再比对 Y」「第 3 步失败，改用 Z」），不要只列正文。

            ## ARTIFACTS
            创建、修改或访问过的文件/资源（含具体路径与变更）。

            ## NEXT STEPS
            为达成目标仍需执行的具体任务。

            只输出提取的上下文，不要多余解释。

            <messages>
            {messages}
            </messages>""";
}
