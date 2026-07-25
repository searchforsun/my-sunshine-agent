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
        /** ReAct 最大 think→tool 循环轮数（SSOT：Nacos agent.execution.react.max-iters） */
        private int maxIters = 5;
        private Taskboard taskboard = new Taskboard();
        /** 4.7.6 spawn_subagent — SSOT：Nacos agent.execution.react.subagent */
        private Subagent subagent = new Subagent();

        @Data
        public static class Taskboard {
            /** true=主 Agent 启用原生 todo_write 任务板（任务列表随 checkpoint 持久化，终态落 MySQL 审计） */
            private boolean enabled = false;
        }

        @Data
        public static class Subagent {
            private boolean enabled = true;
            private int maxIters = 8;
            private long timeoutMs = 180_000L;
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
