package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.agent.runtime.AgentRuntime;
import com.sunshine.orchestrator.catalog.ToolSetResolver;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.SpawnSubagentLabelService;
import com.sunshine.orchestrator.processing.SpawnSubagentLabels;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.prompt.PromptCatalogSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpawnSubagentToolTest {

    private static final String BRIDGE = "main-bridge";
    private static final String MSG = "msg-spawn-tool";

    @Mock
    private AgentRuntime agentRuntime;
    @Mock
    private AgentExecutionProperties executionProperties;
    @Mock
    private AgentExecutionProperties.React reactProps;
    @Mock
    private SpawnSubagentTimelineSupport timelineSupport;
    @Mock
    private ToolSetResolver toolSetResolver;
    @Mock
    private com.sunshine.orchestrator.catalog.AgentCatalogService agentCatalogService;
    @Mock
    private com.sunshine.orchestrator.prompt.TimelinePromptCatalog timelinePromptCatalog;
    private PromptCatalogHolder catalogHolder;

    private SpawnRunRegistry spawnRunRegistry;
    private SpawnSubagentTool tool;
    private StepEventBridgeRegistry registry;

    @BeforeEach
    void setUp() {
        catalogHolder = new PromptCatalogHolder();
        catalogHolder.replace(PromptCatalogSnapshot.of(1L, java.util.List.of(
                new com.sunshine.orchestrator.prompt.PromptCatalogEntry(
                        "react.subagent.cancel-result", "react", "cancel", true, 0, 1,
                        "用户已取消子任务。请主 Agent 自行完成以下任务（勿再次 spawn 同一任务）：\n{prompt}",
                        null))));
        spawnRunRegistry = SpawnRunRegistry.forTest(catalogHolder);
        tool = new SpawnSubagentTool(
                agentRuntime, executionProperties, timelineSupport, toolSetResolver,
                catalogHolder, spawnRunRegistry, agentCatalogService);
        registry = new StepEventBridgeRegistry();
        StepEventBridge.bindRegistry(registry);
        AgentExecutionProperties.React.Subagent sub = new AgentExecutionProperties.React.Subagent();
        sub.setEnabled(true);
        sub.setMaxIters(8);
        sub.setTimeoutMs(5_000L);
        lenient().when(executionProperties.getReact()).thenReturn(reactProps);
        lenient().when(reactProps.getSubagent()).thenReturn(sub);
        lenient().when(toolSetResolver.resolveReactTools(any())).thenReturn(List.of("search_knowledge"));
        SpawnSubagentLabels.bind(new SpawnSubagentLabelService(timelinePromptCatalog));
    }

    @AfterEach
    void tearDown() {
        registry.clearAll();
        StepEventBridge.resetRegistry();
    }

    @Test
    void NAME_equals_spawn_subagent() {
        assertThat(SpawnSubagentTool.NAME).isEqualTo("spawn_subagent");
    }

    @Test
    void emptyPrompt_returnsErrorJson() {
        String out = tool.spawnSubagent("  ", null, null);
        assertThat(out).contains("\"ok\":false");
        assertThat(out).contains("prompt");
    }

    @Test
    void successfulRun_returnsContentAndBeginsTimeline() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        registry.bind(BRIDGE, session, new ConcurrentLinkedQueue<>());
        StepEventBridge.bindHitlBridge(BRIDGE, MSG, true);
        StepEventBridge.registerMainRun(MSG, BRIDGE);
        StepEventBridge.bindToolAudit(MSG, new StepEventBridge.ToolAuditContext(
                "conv-1", MSG, "user-1", "default", null, null, null, null, null));

        when(agentRuntime.run(any())).thenReturn(Flux.just(StreamToken.content("hello")));

        String out = tool.spawnSubagent("请完成子任务", null, "制度检索");

        assertThat(out).isEqualTo("hello");
        ArgumentCaptor<String> runIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(timelineSupport).begin(
                eq(BRIDGE),
                runIdCaptor.capture(),
                eq("制度检索"),
                eq("请完成子任务"));
        assertThat(runIdCaptor.getValue()).isNotBlank();
        verify(timelineSupport).complete(eq(BRIDGE), any(SpawnSubagentTimelineBridge.class), eq("hello"));
    }

    @Test
    void appendAnswerContent_collectsContentAndResultDelta() {
        StringBuilder sb = new StringBuilder();
        SpawnSubagentTool.appendAnswerContent(sb, StreamToken.content("甲"));
        SpawnSubagentTool.appendAnswerContent(sb, StreamToken.stepDelta("think", "reasoning", "勿计入"));
        SpawnSubagentTool.appendAnswerContent(sb, StreamToken.stepDelta("think-2", "result", "乙"));
        assertThat(sb.toString()).isEqualTo("甲乙");
    }

    @Test
    void appendAnswerContent_skipsCumulativeReread() {
        StringBuilder sb = new StringBuilder();
        SpawnSubagentTool.appendAnswerContent(sb, StreamToken.content("甲乙"));
        // AGENT_RESULT 整段复读
        SpawnSubagentTool.appendAnswerContent(sb, StreamToken.content("甲乙"));
        assertThat(sb.toString()).isEqualTo("甲乙");
        // 真续写
        SpawnSubagentTool.appendAnswerContent(sb, StreamToken.content("甲乙丙"));
        assertThat(sb.toString()).isEqualTo("甲乙丙");
    }

    @Test
    void fluxStepTokens_doNotFoldAgain() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        registry.bind(BRIDGE, session, new ConcurrentLinkedQueue<>());
        StepEventBridge.bindHitlBridge(BRIDGE, MSG, true);
        StepEventBridge.registerMainRun(MSG, BRIDGE);
        StepEventBridge.bindToolAudit(MSG, new StepEventBridge.ToolAuditContext(
                "conv-1", MSG, "user-1", "default", null, null, null, null, null));

        when(agentRuntime.run(any())).thenReturn(Flux.just(
                StreamToken.stepDelta("think-1", "reasoning", "用户"),
                StreamToken.content("答案")));

        String out = tool.spawnSubagent("请完成子任务", null, "制度检索");

        assertThat(out).isEqualTo("答案");
        // fold 仅 wrapper（本测 Flux 直出、不经 Hook）；禁止 Flux 再 fold 导致思考翻倍
        verify(timelineSupport, never())
                .fold(any(), any(SpawnSubagentTimelineBridge.class), any());
        verify(timelineSupport).complete(eq(BRIDGE), any(SpawnSubagentTimelineBridge.class), eq("答案"));
    }

    @Test
    void cancelledDuringRun_returnsCancelResultAndDoesNotFailMain() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        registry.bind(BRIDGE, session, new ConcurrentLinkedQueue<>());
        StepEventBridge.bindHitlBridge(BRIDGE, MSG, true);
        StepEventBridge.registerMainRun(MSG, BRIDGE);
        StepEventBridge.bindToolAudit(MSG, new StepEventBridge.ToolAuditContext(
                "conv-1", MSG, "user-1", "default", null, null, null, null, null));

        when(agentRuntime.run(any())).thenAnswer(inv -> {
            com.sunshine.orchestrator.agent.runtime.AgentRunRequest req = inv.getArgument(0);
            spawnRunRegistry.cancel(req.runId());
            return Flux.empty();
        });

        String out = tool.spawnSubagent("请检索制度并汇总", null, "制度检索");

        assertThat(out).contains("用户已取消子任务");
        assertThat(out).contains("请检索制度并汇总");
        ArgumentCaptor<String> runIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(timelineSupport).begin(eq(BRIDGE), runIdCaptor.capture(), eq("制度检索"), eq("请检索制度并汇总"));
        // 取消终态经 SpawnRunRegistry 直写 GenerationJob（本测无 GenerationRegistry，不回落 Hook）
        verify(timelineSupport, never()).fail(any(), any(), any());
        verify(timelineSupport, never()).complete(any(), any(), any());
        assertThat(spawnRunRegistry.get(runIdCaptor.getValue())).isNull();
    }
}
