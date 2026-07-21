package com.sunshine.workflow.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowPlanValidatorTest {

    private static final Map<String, String> RETRY_TOOL = Map.of(
            "retry.maxAttempts", "2",
            "retry.backoffMs", "500",
            "retry.onFailure", "continue");
    private static final Map<String, String> RETRY_AGENT = Map.of(
            "retry.maxAttempts", "1",
            "retry.backoffMs", "500",
            "retry.onFailure", "continue");
    private static final Map<String, String> RETRY_ANSWER = Map.of(
            "retry.maxAttempts", "2",
            "retry.backoffMs", "500",
            "retry.onFailure", "fail_fast");

    private WorkflowPlanValidator validator;

    @BeforeEach
    void setUp() {
        validator = new WorkflowPlanValidator(new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Test
    void rejectsStartToAnswerWithoutBusinessNode() {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("planId", null);
        plan.put("reason", "test");
        plan.put("nodes", List.of(
                startNode(),
                answerNode("请根据用户问题回答。\n\n{{start.userQuery}}")));
        plan.put("edges", List.of(edge("start", "answer")));
        WorkflowPlanValidationResult result = validator.validateDetailed(plan);
        assertThat(result.isValid()).isFalse();
        assertThat(result.issues()).anyMatch(s -> s.contains("业务节点"));
    }

    @Test
    void validLinearToolToAnswer() {
        WorkflowPlanValidationResult result = validator.validateDetailed(linearPlan(
                toolNode("tool-a1b2c3d4", "sdk__sunshine-finance__list_my_expenses"),
                answerNode("请回答\n\n{{tool-a1b2c3d4.output}}")));
        assertThat(result.isValid()).isTrue();
    }

    @Test
    void rejectsDownstreamReference() {
        WorkflowPlanValidationResult result = validator.validateDetailed(linearPlan(
                toolNode("tool-a1b2c3d4", "sdk__sunshine-finance__list_my_expenses",
                        Map.of("tool", "sdk__sunshine-finance__list_my_expenses",
                                "note", "{{tool-b2c3d4e5.output}}")),
                toolNode("tool-b2c3d4e5", "sdk__sunshine-finance__sum"),
                answerNode("{{tool-b2c3d4e5.output}}")));
        assertThat(result.isValid()).isFalse();
        assertThat(result.issues()).anyMatch(s -> s.contains("tool-b2c3d4e5") && s.contains("上游"));
    }

    @Test
    void rejectsInvalidAgentField() {
        WorkflowPlanValidationResult result = validator.validateDetailed(linearPlan(
                toolNode("tool-a1b2c3d4", "sdk__sunshine-finance__list_my_expenses"),
                agentNode("agent-b2c3d4e5", "{{tool-a1b2c3d4.output}}"),
                answerNode("{{tool-a1b2c3d4.answer}}")));
        assertThat(result.isValid()).isFalse();
        assertThat(result.issues()).anyMatch(s -> s.contains(".answer") && s.contains("tool-a1b2c3d4"));
    }

    @Test
    void rejectsUnreachableNode() {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("planId", null);
        plan.put("reason", "test");
        plan.put("nodes", List.of(
                startNode(),
                toolNode("tool-a1b2c3d4", "sdk__x"),
                toolNode("tool-orphan", "sdk__y"),
                answerNode("{{tool-a1b2c3d4.output}}")));
        plan.put("edges", List.of(
                edge("start", "tool-a1b2c3d4"),
                edge("tool-a1b2c3d4", "answer")));
        WorkflowPlanValidationResult result = validator.validateDetailed(plan);
        assertThat(result.isValid()).isFalse();
        assertThat(result.issues()).anyMatch(s -> s.contains("tool-orphan"));
    }

    @Test
    void rejectsRagWithoutQuery() {
        Map<String, Object> params = new LinkedHashMap<>(Map.of(
                "topK", "3",
                "retry.maxAttempts", "1",
                "retry.backoffMs", "500",
                "retry.onFailure", "continue"));
        Map<String, Object> rag = Map.of(
                "id", "rag-a1b2c3d4",
                "type", "rag",
                "displayName", "知识检索",
                "params", params);
        WorkflowPlanValidationResult result = validator.validateDetailed(linearPlan(
                rag,
                answerNode("{{rag-a1b2c3d4.output}}")));
        assertThat(result.isValid()).isFalse();
        assertThat(result.issues()).anyMatch(s -> s.contains("params.query"));
    }

    @Test
    void validLinearRagToAnswer() {
        Map<String, Object> params = new LinkedHashMap<>(Map.of(
                "topK", "3",
                "query", "{{start.userQuery}}",
                "retry.maxAttempts", "1",
                "retry.backoffMs", "500",
                "retry.onFailure", "continue"));
        Map<String, Object> rag = Map.of(
                "id", "rag-a1b2c3d4",
                "type", "rag",
                "displayName", "知识检索",
                "params", params);
        WorkflowPlanValidationResult result = validator.validateDetailed(linearPlan(
                rag,
                answerNode("{{rag-a1b2c3d4.output}}")));
        assertThat(result.isValid()).isTrue();
    }

    @Test
    void validParallelDualRagWithGateway() {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("planId", null);
        plan.put("reason", "dual");
        plan.put("nodes", List.of(
                startNode(),
                gatewayNode("pg-a1b2c3d4", "parallel-gateway", "并行分叉"),
                ragNode("rag-a1b2c3d4", "制度检索"),
                ragNode("rag-e5f6a7b8", "财务检索"),
                gatewayNode("join-c9d0e1f2", "join", "并行汇总"),
                answerNode("制度：{{rag-a1b2c3d4.output}}\n财务：{{rag-e5f6a7b8.output}}")));
        plan.put("edges", List.of(
                edge("start", "pg-a1b2c3d4"),
                edge("pg-a1b2c3d4", "rag-a1b2c3d4"),
                edge("pg-a1b2c3d4", "rag-e5f6a7b8"),
                edge("rag-a1b2c3d4", "join-c9d0e1f2"),
                edge("rag-e5f6a7b8", "join-c9d0e1f2"),
                edge("join-c9d0e1f2", "answer")));
        WorkflowPlanValidationResult result = validator.validateDetailed(plan);
        assertThat(result.isValid()).isTrue();
    }

    @Test
    void rejectsReferenceToParallelGatewayOutput() {
        Map<String, Object> params = new LinkedHashMap<>(Map.of(
                "topK", "3",
                "query", "{{start.userQuery}}",
                "context", "{{pg-a1b2c3d4.output}}",
                "retry.maxAttempts", "1",
                "retry.backoffMs", "500",
                "retry.onFailure", "continue"));
        Map<String, Object> rag = Map.of(
                "id", "rag-a1b2c3d4",
                "type", "rag",
                "displayName", "制度检索",
                "params", params);
        WorkflowPlanValidationResult result = validator.validateDetailed(linearPlan(
                gatewayNode("pg-a1b2c3d4", "parallel-gateway", "并行分叉"),
                rag,
                answerNode("{{rag-a1b2c3d4.output}}")));
        assertThat(result.isValid()).isFalse();
        assertThat(result.issues()).anyMatch(s -> s.contains("pg-a1b2c3d4") && s.contains("网关"));
    }

    @Test
    void validLoopWithRagToolAgentBody() {
        Map<String, Object> loopParams = new LinkedHashMap<>(Map.of(
                "condition.left", "{{start.userQuery}}",
                "condition.op", "contains",
                "condition.right", "继续",
                "maxIterations", "2",
                "onMaxIterations", "exit",
                "retry.maxAttempts", "1",
                "retry.backoffMs", "500",
                "retry.onFailure", "fail_fast"));
        Map<String, Object> loop = new LinkedHashMap<>();
        loop.put("id", "loop-a1b2c3d4");
        loop.put("type", "loop");
        loop.put("displayName", "条件循环");
        loop.put("params", loopParams);

        Map<String, Object> rag = ragNode("rag-l1o2o3p4", "知识检索");
        rag.put("parentId", "loop-a1b2c3d4");
        Map<String, Object> tool = toolNode("tool-t1o2o3p4", "sdk__sunshine-finance__list_my_expenses");
        tool.put("parentId", "loop-a1b2c3d4");
        Map<String, Object> agent = new LinkedHashMap<>(agentNode(
                "agent-a1g2e3n4",
                "检索：{{rag-l1o2o3p4.output}}\n待办：{{tool-t1o2o3p4.output}}"));
        agent.put("parentId", "loop-a1b2c3d4");

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("planId", null);
        plan.put("reason", "loop-body");
        plan.put("nodes", List.of(
                startNode(),
                loop,
                rag,
                tool,
                agent,
                answerNode("{{loop-a1b2c3d4.output}}")));
        plan.put("edges", List.of(
                edge("start", "loop-a1b2c3d4"),
                edge("rag-l1o2o3p4", "tool-t1o2o3p4"),
                edge("tool-t1o2o3p4", "agent-a1g2e3n4"),
                edge("loop-a1b2c3d4", "answer")));
        WorkflowPlanValidationResult result = validator.validateDetailed(plan);
        assertThat(result.issues()).isEmpty();
        assertThat(result.isValid()).isTrue();
    }

    private static Map<String, Object> ragNode(String id, String displayName) {
        Map<String, Object> params = new LinkedHashMap<>(Map.of(
                "topK", "3",
                "query", "{{start.userQuery}}",
                "retry.maxAttempts", "1",
                "retry.backoffMs", "500",
                "retry.onFailure", "continue"));
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("type", "rag");
        node.put("displayName", displayName);
        node.put("params", params);
        return node;
    }

    private static Map<String, Object> gatewayNode(String id, String type, String displayName) {
        Map<String, Object> params = new LinkedHashMap<>(Map.of(
                "retry.maxAttempts", "1",
                "retry.backoffMs", "500",
                "retry.onFailure", "continue"));
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("type", type);
        node.put("displayName", displayName);
        node.put("params", params);
        return node;
    }

    private static Map<String, Object> linearPlan(Map<String, Object>... businessAndAnswer) {
        List<Map<String, Object>> nodes = new java.util.ArrayList<>();
        nodes.add(startNode());
        nodes.addAll(List.of(businessAndAnswer));
        List<Map<String, Object>> edges = new java.util.ArrayList<>();
        String prev = "start";
        for (int i = 1; i < nodes.size(); i++) {
            String id = (String) nodes.get(i).get("id");
            edges.add(edge(prev, id));
            prev = id;
        }
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("planId", null);
        plan.put("reason", "test");
        plan.put("nodes", nodes);
        plan.put("edges", edges);
        return plan;
    }

    private static Map<String, Object> startNode() {
        return Map.of("id", "start", "type", "start", "displayName", "开始", "params", Map.of());
    }

    private static Map<String, Object> toolNode(String id, String tool) {
        return toolNode(id, tool, Map.of("tool", tool));
    }

    private static Map<String, Object> toolNode(String id, String tool, Map<String, Object> params) {
        Map<String, Object> merged = new LinkedHashMap<>(RETRY_TOOL);
        merged.putAll(params);
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("type", "tool");
        node.put("displayName", "工具");
        node.put("params", merged);
        return node;
    }

    private static Map<String, Object> agentNode(String id, String context) {
        Map<String, Object> params = new LinkedHashMap<>(RETRY_AGENT);
        params.put("query", "{{start.userQuery}}");
        params.put("context", context);
        params.put("skill", "finance-analysis");
        params.put("maxIters", "4");
        return Map.of(
                "id", id,
                "type", "agent",
                "displayName", "智能体",
                "params", params);
    }

    private static Map<String, Object> answerNode(String prompt) {
        Map<String, Object> params = new LinkedHashMap<>(RETRY_ANSWER);
        params.put("prompt", prompt);
        return Map.of(
                "id", "answer",
                "type", "answer",
                "displayName", "生成回答",
                "params", params);
    }

    private static Map<String, Object> edge(String from, String to) {
        return Map.of("from", from, "to", to);
    }
}
