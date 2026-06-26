package com.sunshine.orchestrator.plan;

import com.sunshine.orchestrator.catalog.SkillCatalogIndexEntry;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.catalog.ToolCatalogEntry;
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
        when(toolCatalogService.find("list_finance_messages"))
                .thenReturn(Optional.of(new ToolCatalogEntry(
                        "list_finance_messages", "财务列表", "", "remote", "finance", "count", Map.of(), "read")));
        when(skillCatalogService.findIndex("compliance-check"))
                .thenReturn(Optional.of(new SkillCatalogIndexEntry(
                        "compliance-check", "合规审查", "desc", 1, true)));

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
        assertThat(validator.validatePlannerOutput(raw)).contains("Planner 非法节点 type: answer");
    }

    @Test
    void rejectsUnknownTool() {
        when(toolCatalogService.find("unknown_tool")).thenReturn(Optional.empty());
        PlanJson raw = new PlanJson("p", "r",
                List.of(new PlanNode("n1", "tool", Map.of("tool", "unknown_tool"), "查工具")),
                List.of(new PlanEdge("start", "n1")));
        assertThat(validator.validatePlannerOutput(raw)).contains("未知工具");
    }

    @Test
    void acceptsMultiAgentPlanWithTwoAgents() {
        when(toolCatalogService.find("list_finance_messages"))
                .thenReturn(Optional.of(new ToolCatalogEntry(
                        "list_finance_messages", "财务列表", "", "remote", "finance", "count", Map.of(), "read")));
        when(skillCatalogService.findIndex("policy-review"))
                .thenReturn(Optional.of(new SkillCatalogIndexEntry(
                        "policy-review", "制度审查", "desc", 1, true)));
        when(skillCatalogService.findIndex("compliance-check"))
                .thenReturn(Optional.of(new SkillCatalogIndexEntry(
                        "compliance-check", "合规审查", "desc", 1, true)));

        PlanJson raw = multiAgentPlan();
        assertThat(validator.validatePlannerOutput(raw)).isNull();
        PlanJson normalized = PlanNormalizer.normalize(raw);
        assertThat(validator.validate(normalized)).isNull();
        long agentNodes = normalized.nodes().stream().filter(n -> "agent".equals(n.type())).count();
        assertThat(agentNodes).isEqualTo(2);
    }

    private static PlanJson multiAgentPlan() {
        return new PlanJson("p", "制度+财务+合规",
                List.of(
                        new PlanNode("n1", "rag", Map.of("topK", "3"), "检索差旅报销制度"),
                        new PlanNode("n2", "tool",
                                Map.of("tool", "list_finance_messages", "status", "pending"),
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
                        new PlanNode("n1", "tool", Map.of("tool", "list_finance_messages"),
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
