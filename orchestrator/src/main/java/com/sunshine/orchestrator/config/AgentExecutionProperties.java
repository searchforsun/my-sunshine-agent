package com.sunshine.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;

/**
 * Agent 执行模式配置 — react / harness / workflow 运行时策略（Nacos agent.execution）。
 * 提示词正文 SSOT = Catalog（react.subagent.* / planner.harness / harness.worker）。
 */
@Data
@RefreshScope
@ConfigurationProperties(prefix = "agent.execution")
public class AgentExecutionProperties {

    private String defaultMode = "fast";
    private React react = new React();
    /** 4.14 Planner-Executor harness — SSOT：Nacos agent.execution.harness（非 AgentScope HarnessAgent） */
    private Harness harness = new Harness();
    /** Workflow 节点失败终态降级 ReAct — SSOT：Nacos agent.execution.fallback-react */
    private FallbackReact fallbackReact = new FallbackReact();

    @Data
    public static class React {
        /** ReAct 最大 think→tool 循环轮数（chat 会话；SSOT：Nacos agent.execution.react.max-iters） */
        private int maxIters = 5;
        /** task 会话（沙箱任务等长任务）的最大 think→tool 循环轮数，高于 chat 值
         *  （SSOT：Nacos agent.execution.react.task-max-iters） */
        private int taskMaxIters = 100;
        private Taskboard taskboard = new Taskboard();
        /** 4.7.6 spawn_subagent — SSOT：Nacos agent.execution.react.subagent */
        private Subagent subagent = new Subagent();
        /** 4.7.9 request_decision — SSOT：Nacos agent.execution.react.decision */
        private Decision decision = new Decision();
        /** 异步长工具 + await_tool_run — SSOT：Nacos agent.execution.react.async-tool */
        private AsyncTool asyncTool = new AsyncTool();
        /** M3 session_search（会话级正文恢复）— SSOT：Nacos agent.execution.react.session-search */
        private SessionSearch sessionSearch = new SessionSearch();
        /** S-C 双阈值采纳 / 候选动态加载（v3.8）— SSOT：Nacos agent.execution.react.skill-adoption */
        private SkillAdoption skillAdoption = new SkillAdoption();
        /** 4.7.7 L2 目标对齐（GoalAlignmentMiddleware）— SSOT：Nacos agent.execution.react.goal-check */
        private GoalCheck goalCheck = new GoalCheck();
        /** 4.7.7 L3 失败预算（FailureBudgetMiddleware）— SSOT：Nacos agent.execution.react.tool-failure-budget */
        private ToolFailureBudget toolFailureBudget = new ToolFailureBudget();
        /** 5.5 工具语义检索注入（retrieval 分层注入）— SSOT：Nacos agent.execution.react.tool-inject */
        private ToolInject toolInject = new ToolInject();

        /** 4.7.7 L2 目标对齐：周期性把原始问题+任务进度摆回模型面前（MAIN-only，默认关灰度） */
        @Data
        public static class GoalCheck {
            private boolean enabled = false;
            /** 每 N 轮 reasoning 注入一次目标对齐提醒 */
            private int everyNThink = 3;
        }

        /** 4.7.7 L3 失败预算：工具失败达阈值注入强提示防同参数死循环（默认关灰度） */
        @Data
        public static class ToolFailureBudget {
            private boolean enabled = false;
            /** 同 tool+同参数指纹（key 排序 JSON）连续失败阈值 */
            private int sameSignatureMax = 2;
            /** 同 tool 连续失败阈值（工具/数据源整体不可用，强制换方案） */
            private int perToolMax = 3;
        }

        @Data
        public static class Taskboard {
            /** true=主 Agent 启用原生 todo_write 任务板（任务列表随 checkpoint 持久化，终态落 MySQL 审计） */
            private boolean enabled = false;
        }

        @Data
        public static class Subagent {
            private boolean enabled = true;
            private int maxIters = 30;
            private long timeoutMs = 180_000L;
        }

        @Data
        public static class Decision {
            /** D21：默认关，Live/灰度再开 */
            private boolean enabled = false;
            /** 等待用户作答超时（秒）；<=0 表示无限等待，仅用户作答/跳过/停止会话结束 */
            private int timeoutSec = 0;
        }

        @Data
        public static class AsyncTool {
            private boolean enabled = true;
            /** sandbox_exec 单次 await 默认/上限/次数 */
            private int awaitDefaultSec = 30;
            private int awaitMaxSec = 120;
            private int awaitMaxWaits = 3;
            /** spawn_subagent 单次 await（对齐 task 墙钟 600s：3×200） */
            private int spawnAwaitDefaultSec = 120;
            private int spawnAwaitMaxSec = 200;
            private int spawnAwaitMaxWaits = 3;
            /** worker dispatch 单次 await（pro 复杂任务：6×600 观测窗口，允许等待长 Worker） */
            private int workerAwaitDefaultSec = 120;
            private int workerAwaitMaxSec = 600;
            private int workerAwaitMaxWaits = 6;
            private int execWallTimeoutSec = 600;
            private int maxConcurrentPerMessage = 10;
        }

        @Data
        public static class SessionSearch {
            /** true=task 会话 MAIN 注册 sunshine_session_search（chat/workflow 不注册，无影响） */
            private boolean enabled = true;
            /** scope=workspace 跨会话检索的会话数上限（防 convId 列表膨胀；仅最近活跃会话） */
            private int workspaceMaxConvs = 20;
        }

        /**
         * S-C 双阈值采纳 / 候选动态加载（skill-sticky v3.8）。
         * skill：conf &gt; trigger → 直接触发 ≤1（相对差距 delta 校验）；
         * candidate &lt; conf ≤ trigger → 候选（目录提权 + 模型经 sunshine_search_skills 显式加载升级 triggered）。
         * agent：conf ≥ candidate → 可调度池 Top-K（只可调度不自动委派）。
         * 默认关闭——主路径仍 L0 + sticky；关闭时 L3 收集行为与 v3.8 前一致。
         */
        @Data
        public static class SkillAdoption {
            private boolean enabled = false;
            /** 直接触发阈值：conf 严格大于该值才触发（默认高，倾向不触发） */
            private double trigger = 0.85;
            /** 候选阈值：conf 严格大于该值进候选（skill）/ ≥ 该值进可调度池（agent） */
            private double candidate = 0.5;
            /** 相对差距 δ：(最高-次高)/最高 ≥ delta 方可触发；未达标即使过 trigger 也不触发 */
            private double delta = 0.2;
            /** 可调度 agent 池 Top-K */
            private int agentTopK = 5;
            /** 候选 skill 提权上限（目录置顶 + 动态加载） */
            private int candidateTopK = 3;
        }

        /**
         * 5.5 工具语义检索注入（tool RAG）— MAIN 主链按 query 检索 Top-K 注入详细 schema，
         * 全量工具名列表进 Tier 0 稳定前缀（KV cache 友好）。二选一不并存：
         * full = 全量 schema 注入（小工具集兼容，现状）；retrieval = 分层注入。
         */
        @Data
        public static class ToolInject {
            /** full | retrieval */
            private String mode = "full";
            /** retrieval：每轮按 query 注入 Top-K 工具数 */
            private int topK = 8;
            /** retrieval：命中相似度下限（透传 rag-service） */
            private float minScore = 0.30f;
            /** retrieval：检索失败/索引关闭时回退全量注入，保证 ReAct 能力不回退 */
            private boolean fallbackFull = true;
        }
    }

    @Data
    public static class FallbackReact {
        private boolean enabled = true;
        private boolean injectPartialContext = true;
    }

    @Data
    public static class Harness {
        private boolean enabled = false;
        private int maxRounds = 12;
        private int maxTotalTasks = 24;
        private long maxDurationMs = 14_400_000L;
        /** 终态错误降级 ReAct（对齐 workflow 节点 fallbackReact 语义） */
        private FallbackReact fallbackReact = new FallbackReact();
        private Planner planner = new Planner();
        private Worker worker = new Worker();
        private Notebook notebook = new Notebook();

        @Data
        public static class FallbackReact {
            private boolean enabled = true;
        }

        @Data
        public static class Planner {
            private long timeoutMs = 300_000L;
            /** Planner 自身 ReAct 轮数上限（v17：Planner 一次性 run，maxIters 兜底；SSOT：Nacos agent.execution.harness.planner.max-iters） */
            private int maxIters = 30;
        }

        @Data
        public static class Worker {
            private long timeoutMs = 3_600_000L;
        }

        @Data
        public static class Notebook {
            private long redisTtlSeconds = 604_800L;
            private String keyPrefix = "sunshine:plan:notebook:";
            private Compression compression = new Compression();

            @Data
            public static class Compression {
                private int nearKeepRounds = 10;
            }
        }
    }

    private As2 as2 = new As2();

    @Data
    public static class As2 {
        /** AgentState Redis TTL 秒（spec §4.1 锁定 7 天） */
        private long stateTtlSec = 604800L;
        /** AgentState Redis key 前缀（与 GenerationJob 隔离） */
        private String stateKeyPrefix = "agentscope:state:";
    }
}
