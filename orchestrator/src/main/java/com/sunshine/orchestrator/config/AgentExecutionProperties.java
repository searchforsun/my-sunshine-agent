package com.sunshine.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
        private List<String> tools = new ArrayList<>(List.of(
                "search_knowledge",
                "list_finance_messages",
                "get_finance_message_detail",
                "summarize_finance_by_status",
                "list_oa_tasks"));
        private int maxIters = 5;
    }

    @Data
    public static class PlanWorkflow {
        private Replan replan = new Replan();
        private PlannerInvoke planner = new PlannerInvoke();
        private Node node = new Node();
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
        public static class Node {
            private NodeDefaults defaults = new NodeDefaults();
            private Map<String, NodeTypeOverride> byType = defaultByType();
            private List<String> criticalTools = new ArrayList<>(List.of(
                    "list_finance_messages", "get_finance_message_detail"));
            private String criticalOnFailure = "fail_fast";

            private static Map<String, NodeTypeOverride> defaultByType() {
                Map<String, NodeTypeOverride> map = new LinkedHashMap<>();
                NodeTypeOverride rag = new NodeTypeOverride();
                rag.setMaxAttempts(1);
                map.put("rag", rag);
                NodeTypeOverride tool = new NodeTypeOverride();
                tool.setMaxAttempts(2);
                map.put("tool", tool);
                NodeTypeOverride agent = new NodeTypeOverride();
                agent.setMaxAttempts(1);
                map.put("agent", agent);
                NodeTypeOverride answer = new NodeTypeOverride();
                answer.setMaxAttempts(2);
                answer.setOnFailure("fail_fast");
                map.put("answer", answer);
                return map;
            }
        }

        @Data
        public static class NodeDefaults {
            private int maxAttempts = 2;
            private long backoffMs = 500;
            private double backoffMultiplier = 2.0;
            private String onFailure = "continue";
            private List<String> retryOnErrorClass = new ArrayList<>(List.of(
                    "TIMEOUT", "SERVICE_UNAVAILABLE", "CIRCUIT_OPEN"));
        }

        @Data
        public static class NodeTypeOverride {
            private int maxAttempts;
            private String onFailure;
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
    }
}
