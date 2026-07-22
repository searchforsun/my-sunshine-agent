package com.sunshine.orchestrator.context;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;

/**
 * 三层上下文运行参数 — 数值/开关 SSOT：Nacos {@code agent.context.*}。
 * AutoContext（单次 ReAct TOOL 压缩）仍在 {@code agent.memory.auto-context}。
 */
@Getter
@Setter
@RefreshScope
@ConfigurationProperties(prefix = "agent.context")
public class ContextProperties {

    private boolean enabled = true;

    private L1 l1 = new L1();
    private L2 l2 = new L2();
    private L3 l3 = new L3();
    private Maintenance maintenance = new Maintenance();

    @Getter
    @Setter
    public static class L1 {
        /** 近窗保留的问答轮次数（一轮 = 1 次 user + 其后 assistant），非消息条数。 */
        private int nearTurns = 8;
        /** 中窗轮次数；仅压缩该窗内 assistant 答案。 */
        private int midTurns = 8;
        private int maxChars = 12000;
    }

    @Getter
    @Setter
    public static class L2 {
        private double minConfidence = 0.75;
        private double constraintOverwriteConfidence = 0.9;
        private int preferenceTtlDays = 365;
        private int agreementTtlDays = 365;
        private int goalTtlDays = 90;
        private int decisionTtlDays = 90;
        private int factTtlDays = 30;
        private int constraintTtlDays = 30;
    }

    @Getter
    @Setter
    public static class L3 {
        private String collection = "sunshine_chat_history";
        private int topK = 5;
        private double minScore = 0.55;
        private boolean timeDecay = true;
    }

    @Getter
    @Setter
    public static class Maintenance {
        /** ms；仿 SandboxSessionReaper */
        private long intervalMs = 3_600_000L;
        /** superseded 行保留天数，超时物理删除 */
        private int supersededRetentionDays = 180;
        /** void 行保留天数，超时物理删除；≤0 表示不清理 */
        private int voidRetentionDays = 30;
        /** 腐败/矛盾审计总开关 */
        private boolean auditEnabled = true;
        /** L2 抽取后轻量审计 */
        private boolean auditOnExtract = true;
        /** 小时维护每 tick 最多审阅用户数 */
        private int auditMaxUsersPerTick = 50;
        /** 同一用户抽取后审计防抖（ms） */
        private long auditExtractDebounceMs = 30_000L;
    }
}
