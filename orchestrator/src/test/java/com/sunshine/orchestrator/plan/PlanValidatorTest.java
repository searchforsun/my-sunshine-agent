package com.sunshine.orchestrator.plan;

import com.sunshine.orchestrator.catalog.SkillCatalogIndexEntry;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.common.tool.ToolCatalogEntry;
import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.config.AgentPromptProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlanValidatorTest {

    @Mock
    private SkillCatalogService skillCatalogService;
    @Mock
    private ToolCatalogService toolCatalogService;

    private PlanValidator validator;

    @BeforeEach
    void setUp() {
        AgentPromptProperties props = new AgentPromptProperties();
        validator = new PlanValidator(skillCatalogService, toolCatalogService, props);
    }

    @Test
    void acceptsValidPlan() {
        when(toolCatalogService.find("sdk__sunshine-finance__list_finance_messages"))
                .thenReturn(Optional.of(new ToolCatalogEntry(
                        "sdk__sunshine-finance__list_finance_messages", "财务列表", "", "remote", "sdk", "sunshine-finance", "", null, Map.of(), "read", false, true, true, null)));
        when(skillCatalogService.findIndex("compliance-check"))
                .thenReturn(Optional.of(new SkillCatalogIndexEntry(
                        "compliance-check", "合规审查", "desc", 1, true, "none")));

        PlanJson raw = samplePlan();
        assertThat(validator.validatePlannerOutput(raw)).isNull();
        PlanJson normalized = PlanNormalizer.normalize(raw);
        assertThat(validator.validate(normalized)).isNull();
    }

    @Test
    void rejectsPlannerAnswerNode() {
        PlanJson raw = new PlanJson("p", "r",
                List.of(
                        new PlanNode("n1", "rag", Map.of(), "检索"),
                        new PlanNode("n4", "answer", Map.of(), "生成回答")),
                List.of(new PlanEdge("start", "n1"), new PlanEdge("n1", "n4")));
        assertThat(validator.validatePlannerOutput(raw).message()).contains("Planner 非法节点 type: answer");
    }

    @Test
    void rejectsUnknownTool() {
        when(toolCatalogService.find("unknown_tool")).thenReturn(Optional.empty());
        PlanJson raw = new PlanJson("p", "r",
                List.of(new PlanNode("n1", "tool", Map.of("tool", "unknown_tool"), "查工具")),
                List.of(new PlanEdge("start", "n1")));
        assertThat(validator.validatePlannerOutput(raw).message()).contains("未知工具");
    }

    @Test
    void acceptsMultiAgentPlanWithTwoAgents() {
        when(toolCatalogService.find("sdk__sunshine-finance__list_finance_messages"))
                .thenReturn(Optional.of(new ToolCatalogEntry(
                        "sdk__sunshine-finance__list_finance_messages", "财务列表", "", "remote", "sdk", "sunshine-finance", "", null, Map.of(), "read", false, true, true, null)));
        when(skillCatalogService.findIndex("policy-review"))
                .thenReturn(Optional.of(new SkillCatalogIndexEntry(
                        "policy-review", "制度审查", "desc", 1, true, "none")));
        when(skillCatalogService.findIndex("compliance-check"))
                .thenReturn(Optional.of(new SkillCatalogIndexEntry(
                        "compliance-check", "合规审查", "desc", 1, true, "none")));

        PlanJson raw = multiAgentPlan();
        assertThat(validator.validatePlannerOutput(raw)).isNull();
        PlanJson normalized = PlanNormalizer.normalize(raw);
        assertThat(validator.validate(normalized)).isNull();
        long agentNodes = normalized.nodes().stream().filter(n -> "agent".equals(n.type())).count();
        assertThat(agentNodes).isEqualTo(2);
    }

    @Test
    void acceptsPlannerParallelGatewayPlan() {
        PlanJson raw = new PlanJson("p", "并行",
                List.of(
                        new PlanNode("pg-1", "parallel-gateway", Map.of(), "并行分叉"),
                        new PlanNode("rag-a", "rag", Map.of("topK", "3"), "制度检索"),
                        new PlanNode("rag-b", "rag", Map.of("topK", "3"), "财务检索"),
                        new PlanNode("join-1", "join", Map.of(), "汇总")),
                List.of(
                        new PlanEdge("start", "pg-1"),
                        new PlanEdge("pg-1", "rag-a"),
                        new PlanEdge("pg-1", "rag-b"),
                        new PlanEdge("rag-a", "join-1"),
                        new PlanEdge("rag-b", "join-1")));
        assertThat(validator.validatePlannerOutput(raw)).isNull();
        PlanJson normalized = PlanNormalizer.normalize(raw);
        assertThat(validator.validate(normalized)).isNull();
    }

    @Test
    void acceptsPlannerExclusiveGatewayPlan() {
        PlanJson raw = new PlanJson("p", "条件",
                List.of(
                        new PlanNode("xg-1", "exclusive-gateway", Map.of(), "条件分支"),
                        new PlanNode("rag-hit", "rag", Map.of("topK", "3"), "命中检索"),
                        new PlanNode("rag-miss", "rag", Map.of("topK", "3"), "兜底检索")),
                List.of(
                        new PlanEdge("start", "xg-1"),
                        new PlanEdge("xg-1", "rag-hit",
                                new PlanEdgeCondition("{{start.userQuery}}", "contains", "报销"), false),
                        new PlanEdge("xg-1", "rag-miss", null, true)));
        assertThat(validator.validatePlannerOutput(raw)).isNull();
        assertThat(validator.validate(PlanNormalizer.normalize(raw))).isNull();
    }

    @Test
    void acceptsPlannerLoopPlan() {
        PlanJson raw = new PlanJson("p", "循环",
                List.of(
                        new PlanNode("loop-1", "loop", Map.of(
                                "condition.left", "{{start.userQuery}}",
                                "condition.op", "contains",
                                "condition.right", "继续",
                                "maxIterations", "2",
                                "onMaxIterations", "exit"), "循环", null),
                        new PlanNode("rag-body", "rag", Map.of("topK", "3"), "框内检索", "loop-1")),
                List.of(new PlanEdge("start", "loop-1")));
        assertThat(validator.validatePlannerOutput(raw)).isNull();
        assertThat(validator.validate(PlanNormalizer.normalize(raw))).isNull();
    }

    private static PlanJson multiAgentPlan() {
        return new PlanJson("p", "制度+财务+合规",
                List.of(
                        new PlanNode("n1", "rag", Map.of("topK", "3"), "检索差旅报销制度"),
                        new PlanNode("n2", "tool",
                                Map.of("tool", "sdk__sunshine-finance__list_finance_messages", "status", "pending"),
                                "查询待审批报销单"),
                        new PlanNode("n3", "agent",
                                Map.of("skill", "policy-review", "context", "{{n1.output}}",
                                        "query", "归纳制度中与差旅报销相关的要点"),
                                "制度解读"),
                        new PlanNode("n4", "agent",
                                Map.of("skill", "compliance-check",
                                        "context", "{{n1.output}}\n{{n2.output}}\n{{n3.output}}",
                                        "query", "对每条待审批单据做合规审查并归纳风险"),
                                "合规分析")),
                List.of(
                        new PlanEdge("start", "n1"),
                        new PlanEdge("n1", "n2"),
                        new PlanEdge("n2", "n3"),
                        new PlanEdge("n3", "n4")));
    }

    private static PlanJson samplePlan() {
        return new PlanJson("p", "r",
                List.of(
                        new PlanNode("n1", "tool", Map.of("tool", "sdk__sunshine-finance__list_finance_messages"),
                                "查询待审批"),
                        new PlanNode("n2", "agent",
                                Map.of("skill", "compliance-check", "context", "{{n1.output}}",
                                        "query", "{{start.userQuery}}"),
                                "合规分析")),
                List.of(
                        new PlanEdge("start", "n1"),
                        new PlanEdge("n1", "n2")));
    }
}
