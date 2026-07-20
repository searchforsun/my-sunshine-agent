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
            private boolean enabled = false;
            private int maxItems = 12;
            private int maxInProgress = 1;
            private boolean seedFromInjectedSummary = true;
            private Audit audit = new Audit();

            @Data
            public static class Audit {
                private double sampleRate = 1.0;
            }
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
}
