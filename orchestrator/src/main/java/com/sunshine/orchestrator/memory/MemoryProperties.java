package com.sunshine.orchestrator.memory;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;

/**
 * 三层记忆运行参数 — 数值/开关 SSOT：Nacos {@code agent.memory.*}。
 * 提示词正文（layer / STM header·preamble / current-user-marker）SSOT = Catalog。
 */
@Getter
@Setter
@RefreshScope
@ConfigurationProperties(prefix = "agent.memory")
public class MemoryProperties {

    private boolean enabled = true;

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
