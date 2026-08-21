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
