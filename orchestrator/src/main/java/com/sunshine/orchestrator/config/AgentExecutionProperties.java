package com.sunshine.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 执行模式配置 — react 工具白名单、plan-workflow 重试降级（Nacos agent.execution）
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
    }

    @Data
    public static class PlanWorkflow {
        private Replan replan = new Replan();
        private PlannerInvoke planner = new PlannerInvoke();
        private Answer answer = new Answer();
        private FallbackReact fallbackReact = new FallbackReact();

        @Data
        public static class Replan {
            private int maxAttempts = 2;
            private String userFeedbackTemplate = """
                    上次规划未通过校验：{{error}}
                    请修正后重新输出一行 JSON。仍须遵守：仅 rag/tool/agent；勿含 start/answer；edges 末节点勿连 answer。""";
        }

        @Data
        public static class PlannerInvoke {
            private int maxAttempts = 2;
            private long backoffMs = 800;
        }

        @Data
        public static class Answer {
            private String upstreamFailureLine = "（{{displayName}} 执行失败：{{error}}，已尝试 {{attemptCount}} 次）";
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
            private String userModificationTemplate = """
                    用户对当前执行计划的修改意见：{{hint}}
                    请据此重新输出一行 Plan JSON。仍须遵守：仅 rag/tool/agent；勿含 start/answer；edges 末节点勿连 answer。""";
        }

        private Approval approval = new Approval();
    }
}
