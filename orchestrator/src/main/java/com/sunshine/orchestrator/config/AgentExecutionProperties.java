package com.sunshine.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;

/**
 * Agent 执行模式配置 — react / plan-workflow 运行时策略（Nacos agent.execution）。
 * 提示词正文 SSOT = Catalog（react.subagent.* / plan-workflow.*）。
 */
@Data
@RefreshScope
@ConfigurationProperties(prefix = "agent.execution")
public class AgentExecutionProperties {

    private String defaultMode = "react";
    private React react = new React();
    private PlanWorkflow planWorkflow = new PlanWorkflow();

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
            /** chat 会话 spawn 超时（SSOT：Nacos …subagent.timeout-ms） */
            private long timeoutMs = 300_000L;
            /** task 会话 spawn 超时（SSOT：Nacos …subagent.task-timeout-ms） */
            private long taskTimeoutMs = 600_000L;
        }

        @Data
        public static class Decision {
            /** D21：默认关，Live/灰度再开 */
            private boolean enabled = false;
            private int timeoutSec = 300;
        }

        @Data
        public static class AsyncTool {
            private boolean enabled = true;
            private int awaitDefaultSec = 30;
            private int awaitMaxSec = 120;
            private int awaitMaxWaits = 3;
            private int execWallTimeoutSec = 600;
            private int maxConcurrentPerMessage = 3;
        }
    }

    @Data
    public static class PlanWorkflow {
        private Replan replan = new Replan();
        private PlannerInvoke planner = new PlannerInvoke();
        private Answer answer = new Answer();
        private FallbackReact fallbackReact = new FallbackReact();
        private Approval approval = new Approval();

        @Data
        public static class Replan {
            private int maxAttempts = 2;
        }

        @Data
        public static class PlannerInvoke {
            private int maxAttempts = 2;
            private long backoffMs = 800;
        }

        @Data
        public static class Answer {
        }

        @Data
        public static class FallbackReact {
            private boolean enabled = true;
            private boolean injectPartialContext = true;
        }

        @Data
        public static class Approval {
            private boolean enabled = true;
            private int timeoutSec = 600;
            private int maxUserRounds = 10;
            /** 超时策略：fallback_react 降级 ReAct；auto_approve 视同用户确认并执行 Plan */
            private String onTimeout = "fallback_react";
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
