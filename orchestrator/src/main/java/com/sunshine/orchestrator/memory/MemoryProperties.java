package com.sunshine.orchestrator.memory;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;

/**
 * 三层记忆配置 — SSOT 见 Nacos {@code agent.memory.*}（docs/nacos/sunshine-orchestrator.yaml）。
 */
@Getter
@Setter
@RefreshScope
@ConfigurationProperties(prefix = "agent.memory")
public class MemoryProperties {

    private boolean enabled = true;

    /** 记忆分层总说明 — 注入在 LTM/MTM/STM 块之前 */
    private String layerPrompt = """
            记忆分层：LTM/MTM 为摘要，STM 为同会话已结束轮次（仅供指代）。
            **仅执行并回答**带「【当前提问 · 仅此作答】」标记的用户消息。""";

    /** 当前 user 消息前缀，与历史记忆块明显区分 */
    private String currentUserMarker = "【当前提问 · 仅此作答】";

    private Stm stm = new Stm();
    private Mtm mtm = new Mtm();
    private Ltm ltm = new Ltm();
    /** ReAct 单次 run 内 TOOL 上下文压缩（AgentScope AutoContextMemory） */
    private AutoContext autoContext = new AutoContext();

    @Getter
    @Setter
    public static class Stm {
        /** Redis 会话缓存 TTL（小时） */
        private int redisTtlHours = 24;
        /** 注入 LLM 的最近消息条数上限 */
        private int maxMessages = 12;
        /** STM 块字符上限 */
        private int maxChars = 8000;
        private String header = "[本会话近期对话 · STM]";
        private String preamble = """
                以下为同会话已结束轮次，仅供指代与消歧（如「这个 skill」「上述脚本」）。""";
    }

    @Getter
    @Setter
    public static class Mtm {
        private boolean enabled = true;
        private int topK = 3;
        private float minScore = 0.55f;
    }

    @Getter
    @Setter
    public static class Ltm {
        private boolean enabled = true;
        /** 画像注入字符上限 */
        private int maxChars = 500;
    }

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
