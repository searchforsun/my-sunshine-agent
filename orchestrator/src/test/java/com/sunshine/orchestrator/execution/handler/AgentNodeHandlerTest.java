package com.sunshine.orchestrator.execution.handler;

import com.sunshine.orchestrator.catalog.SkillCatalogEntry;
import com.sunshine.orchestrator.agent.runtime.AgentRole;
import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.agent.runtime.AgentRuntime;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.conversation.ChatTurn;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.NodeSpec;
import com.sunshine.orchestrator.execution.WorkflowContext;
import com.sunshine.orchestrator.grounding.AnswerGroundingChecker;
import com.sunshine.orchestrator.grounding.GroundingVerdict;
import com.sunshine.orchestrator.memory.MemoryContext;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentNodeHandlerTest {

    @Mock
    private AgentRuntime agentRuntime;

    @Mock
    private SkillCatalogService skillCatalogService;

    @Mock
    private com.sunshine.orchestrator.audit.SubAgentAuditService subAgentAuditService;

    @Mock
    private AnswerGroundingChecker groundingChecker;

    @InjectMocks
    private AgentNodeHandler handler;

    @BeforeEach
    void stubGroundingPass() {
        when(groundingChecker.check(any(), any())).thenReturn(GroundingVerdict.pass());
    }

    @Test
    void run_buildsSubAgentRunRequestAndCollectsAnswer() {
        when(agentRuntime.run(any(AgentRunRequest.class)))
                .thenReturn(Flux.just(
                        StreamToken.content("待审批 5 笔单据存在合规风险，建议优先处理 1004。")));

        WorkflowContext ctx = new WorkflowContext();
        ExecutionStreamContext streamCtx = new ExecutionStreamContext(
                "c1", "m1", "待审批是否合规", MemoryContext.empty(),
                null, null, null, "u1", "default",
                new ExecutionPlan(ExecutionMode.WORKFLOW, "finance-smart", Map.of(), "test"));

        NodeSpec spec = new NodeSpec("analyze", "agent",
                Map.of("query", "待审批是否合规", "context", "制度摘要"));

        var result = handler.run(spec, ctx, streamCtx).block();
        assertThat(result).isNotNull();
        assertThat(result.success()).isTrue();
        assertThat(result.safeOutputs().get("answer")).contains("合规风险");
        assertThat(result.safeOutputs().get("detail")).contains("合规风险");

        ArgumentCaptor<AgentRunRequest> captor = ArgumentCaptor.forClass(AgentRunRequest.class);
        verify(agentRuntime).run(captor.capture());
        AgentRunRequest req = captor.getValue();
        assertThat(req.role()).isEqualTo(AgentRole.SUB);
        assertThat(req.query()).isEqualTo("待审批是否合规");
        assertThat(req.assistantMessageId()).isNull();
        assertThat(req.injectedBlocks()).containsExactly("制度摘要");
        assertThat(req.memory()).isEqualTo(MemoryContext.forSubAgent());
        assertThat(req.memory().stmTurns()).isEmpty();
        assertThat(req.skillId()).isNull();
        assertThat(req.toolWhitelist()).isNull();
        assertThat(req.systemOverlay()).isNull();
        assertThat(req.maxIters()).isZero();
    }

    @Test
    void run_parsesSkillToolsOverlayAndMaxIters() {
        when(agentRuntime.run(any(AgentRunRequest.class)))
                .thenReturn(Flux.just(StreamToken.content("分析完成")));

        WorkflowContext ctx = new WorkflowContext();
        ExecutionStreamContext streamCtx = new ExecutionStreamContext(
                "c1", "m1", "q", MemoryContext.empty(),
                null, null, null, "u1", "default",
                new ExecutionPlan(ExecutionMode.WORKFLOW, "finance-smart", Map.of(), "test"));

        NodeSpec spec = new NodeSpec("analyze", "agent", Map.of(
                "query", "待审批是否合规",
                "context", "财务列表 JSON",
                "skill", "finance-analysis",
                "tools", "list_finance_messages,search_knowledge",
                "maxIters", "4",
                "systemOverlay", "仅输出内部分析结论"));

        handler.run(spec, ctx, streamCtx).block();

        ArgumentCaptor<AgentRunRequest> captor = ArgumentCaptor.forClass(AgentRunRequest.class);
        verify(agentRuntime).run(captor.capture());
        AgentRunRequest req = captor.getValue();
        assertThat(req.skillId()).isEqualTo("finance-analysis");
        assertThat(req.toolWhitelist()).containsExactly("list_finance_messages", "search_knowledge");
        assertThat(req.systemOverlay()).isEqualTo("仅输出内部分析结论");
        assertThat(req.maxIters()).isEqualTo(4);
        assertThat(req.memory()).isEqualTo(MemoryContext.forSubAgent());
    }

    @Test
    void run_ignoresStreamContextMemoryForSubAgent() {
        when(agentRuntime.run(any(AgentRunRequest.class)))
                .thenReturn(Flux.just(StreamToken.content("ok")));

        MemoryContext fullMemory = new MemoryContext("ltm", "mtm", List.of(
                new ChatTurn("user", "上一轮"), new ChatTurn("assistant", "上一轮答")));
        ExecutionStreamContext streamCtx = new ExecutionStreamContext(
                "c1", "m1", "q", fullMemory,
                null, null, null, "u1", "default",
                new ExecutionPlan(ExecutionMode.WORKFLOW, "finance-smart", Map.of(), "test"));
        NodeSpec spec = new NodeSpec("analyze", "agent", Map.of("query", "子任务", "context", "ctx"));

        handler.run(spec, new WorkflowContext(), streamCtx).block();

        ArgumentCaptor<AgentRunRequest> captor = ArgumentCaptor.forClass(AgentRunRequest.class);
        verify(agentRuntime).run(captor.capture());
        assertThat(captor.getValue().memory()).isEqualTo(MemoryContext.forSubAgent());
        assertThat(captor.getValue().memory().stmTurns()).isEmpty();
    }

    @Test
    void run_expandDetailPrefixesLoadedSkill() {
        when(skillCatalogService.find("finance-analysis")).thenReturn(Optional.of(
                new SkillCatalogEntry("finance-analysis", "财务合规分析", "d", "overlay", 2, true)));
        when(agentRuntime.run(any(AgentRunRequest.class)))
                .thenReturn(Flux.just(StreamToken.content("无法判断的合规要素")));

        NodeSpec spec = new NodeSpec("analyze", "agent", Map.of(
                "query", "待审批是否合规",
                "context", "ctx",
                "skill", "finance-analysis"));
        WorkflowContext ctx = new WorkflowContext();
        ExecutionStreamContext streamCtx = new ExecutionStreamContext(
                "c1", "m1", "q", MemoryContext.empty(),
                null, null, null, "u1", "default",
                new ExecutionPlan(ExecutionMode.WORKFLOW, "finance-smart", Map.of(), "test"));
        var result = handler.run(spec, ctx, streamCtx).block();

        assertThat(result).isNotNull();
        assertThat(result.safeOutputs().get("answer")).isEqualTo("无法判断的合规要素");
        assertThat(result.safeOutputs().get("expandDetail"))
                .startsWith("已加载技能：财务合规分析\n\n无法判断的合规要素");
    }

    @Test
    void run_skillWithoutNodeTools_leavesWhitelistNull() {
        when(agentRuntime.run(any(AgentRunRequest.class)))
                .thenReturn(Flux.just(StreamToken.content("ok")));

        NodeSpec spec = new NodeSpec("analyze", "agent", Map.of(
                "query", "子任务",
                "context", "ctx",
                "skill", "finance-analysis"));
        WorkflowContext ctx = new WorkflowContext();
        ExecutionStreamContext streamCtx = new ExecutionStreamContext(
                "c1", "m1", "q", MemoryContext.empty(),
                null, null, null, "u1", "default",
                new ExecutionPlan(ExecutionMode.WORKFLOW, "finance-smart", Map.of(), "test"));
        handler.run(spec, ctx, streamCtx).block();

        ArgumentCaptor<AgentRunRequest> captor = ArgumentCaptor.forClass(AgentRunRequest.class);
        verify(agentRuntime).run(captor.capture());
        assertThat(captor.getValue().toolWhitelist()).isNull();
        assertThat(captor.getValue().maxIters()).isZero();
    }

    @Test
    void run_blocksSubAgentOutputWhenGroundingFails() {
        when(agentRuntime.run(any(AgentRunRequest.class)))
                .thenReturn(Flux.just(StreamToken.content("报销上限为 5000 元。")));
        when(groundingChecker.check(any(), any())).thenReturn(
                GroundingVerdict.fail("未经验证", List.of("5000 元")));

        WorkflowContext ctx = new WorkflowContext();
        ExecutionStreamContext streamCtx = new ExecutionStreamContext(
                "c1", "m1", "报销上限", MemoryContext.empty(),
                null, null, null, "u1", "default",
                new ExecutionPlan(ExecutionMode.WORKFLOW, "finance-smart", Map.of(), "test"));

        NodeSpec spec = new NodeSpec("analyze", "agent", Map.of("query", "报销上限"));

        var result = handler.run(spec, ctx, streamCtx).block();
        assertThat(result).isNotNull();
        assertThat(result.success()).isFalse();
        assertThat(result.safeOutputs().get("error")).contains("未经验证");
    }

    @Test
    void parseToolList_splitsBracketYamlList() {
        assertThat(AgentNodeHandler.parseToolList("[list_finance_messages]"))
                .containsExactly("list_finance_messages");
    }

    @Test
    void parseToolList_splitsCommaSeparatedNames() {
        assertThat(AgentNodeHandler.parseToolList("list_finance_messages, search_knowledge"))
                .containsExactly("list_finance_messages", "search_knowledge");
        assertThat(AgentNodeHandler.parseToolList("  ")).isNull();
    }

    @Test
    void parseMaxIters_ignoresInvalidValues() {
        assertThat(AgentNodeHandler.parseMaxIters("4")).isEqualTo(4);
        assertThat(AgentNodeHandler.parseMaxIters("0")).isZero();
        assertThat(AgentNodeHandler.parseMaxIters("bad")).isZero();
    }
}
