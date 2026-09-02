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
        /** 压缩触发阈值（模型上下文窗口占比），达到即触发压缩。 */
        private double maxTokensRatio = 0.8;
        /** 轮次宽限兜底：即使 token 未到阈值，轮数超此值也触发（防极端短消息无限膨胀）。 */
        private int turnBackstop = 40;
        /** cl100k 估算保守系数（对 deepseek/qwen 偏高 5-15%，提前触发留 buffer）。 */
        private double tokenSafetyFactor = 1.1;
        /** Mid 摘要后 token 估算比（1-3 句摘要约为原文 15%）。 */
        private double midCompressRatio = 0.15;

        /** 压缩点模式（五层 §5.5 / task-scene §4）；启用面 task×fast|pro。 */
        private CompressionPoint compressionPoint = new CompressionPoint();

        /**
         * 压缩点模式（§5.5）：Near 起点 = {@code far_folded_msg_ids} 之后，只增不减；
         * 溢出走「同步推进压缩点 + 异步折叠」，不从 Near 头部丢轮次。
         */
        @Getter
        @Setter
        public static class CompressionPoint {
            /** 总开关；启用面仍受 kind×executionMode 门控（task×fast|pro + chat×fast|pro，task-scene §2.2）。 */
            private boolean enabled = true;
            /** task 压缩后 Near 保底原文轮数（v15：2+2+Far）。 */
            private int nearKeepRounds = 2;
            /** task 压缩后 Mid 摘要轮数。 */
            private int midKeepRounds = 2;
            /** chat 压缩后 Near 保底原文轮数（§5.5.7：4+4+Far，二期跟随）。 */
            private int chatNearKeepRounds = 4;
            /** chat 压缩后 Mid 摘要轮数。 */
            private int chatMidKeepRounds = 4;
            /**
             * task 压缩后重组硬性总量预算（Near+Mid+Far，token；v15）。
             * 超限先降级最旧 Mid 轮为折叠、再极端折叠最旧 Near（保底 1 轮）。≤0 关闭。
             * chat 无硬预算（§5.5.7 差异表：仅 task 4+4+Far≤10k），靠组装侧 Budget 退役并入收敛。
             */
            private int taskPostCompactBudget = 10_000;
        }
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
        /** 过程笔记（process_note；原 reasoning/option/interim_conclusion/topic 合并）分级置信门禁。 */
        private double processNoteMinConfidence = 0.65;
        /** 过程笔记 TTL（易过时）。 */
        private int processNoteTtlDays = 7;
        /** todo（未完成任务清单）TTL，短生命周期。 */
        private int todoTtlDays = 7;
        /** 语义 merge 开关（§6.4）：关闭回退纯字面快路径（兼容现行为）。 */
        private boolean semanticMergeEnabled = true;
    }

    @Getter
    @Setter
    public static class L3 {
        private String collection = "sunshine_chat_history";
        private int topK = 5;
        private double minScore = 0.55;
        private boolean timeDecay = true;
        /** 时间衰减半衰期（天）：score *= 0.5^(ageDays / halfLife)。 */
        private int decayHalfLifeDays = 90;
        /** v26 语义提取层开关；攒批触发 + turn-pair 合并（§7.4.1/§7.4.2）。 */
        private boolean semanticExtractEnabled = true;
        /**
         * v26.2 body 层非全量（置信门禁）：语义提取按轮判定后，abstain 轮 body 原文也不落库
         * （仅重要轮 body+semantic 双写），避免确认语/寒暄/长答案等噪音长期污染向量空间。
         * 关闭时回退既有两路并存（body 即时全量 + semantic）。
         */
        private boolean bodyGateEnabled = true;
        /** 攒批触发轮数（默认 3 轮累积后批量提取）。 */
        private int semanticBatchTurns = 3;
        /** 攒批触发间隔 ms（默认 5 分钟）。 */
        private long semanticBatchIntervalMs = 300_000L;
        /** 语义提取层入库去重开关（§7.4.3）。 */
        private boolean semanticDedupeEnabled = true;
        /** 去重完全重复阈值：cosine > 此值跳过。 */
        private double dedupeSkipThreshold = 0.95;
        /** 去重合并阈值：0.85~0.95 视为同义合并（保留旧条）。 */
        private double dedupeMergeThreshold = 0.85;
        /** v26 task process 层（工具结果摘要向量化）开关。 */
        private boolean processLayerEnabled = true;
        /** process 层每条 result 截断字符数。 */
        private int processResultMaxChars = 200;
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
        /** v26 L3 过期清理分层 TTL（天；≤0 跳过对应层）：chat 全层 / task body / task process / task semantic。 */
        private int l3ChatTtlDays = 30;
        private int l3TaskBodyTtlDays = 90;
        private int l3TaskProcessTtlDays = 7;
        private int l3TaskSemanticTtlDays = 90;
    }
}
