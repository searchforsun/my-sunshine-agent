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
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private AgentExecutorRouter agentExecutorRouter;
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
    private com.sunshine.orchestrator.catalog.SkillCatalogService skillCatalogService;
    @Mock
    private com.sunshine.orchestrator.prompt.TimelinePromptCatalog timelinePromptCatalog;
    private PromptCatalogHolder catalogHolder;

    private SpawnRunRegistry spawnRunRegistry;
    private AsyncToolRunRegistry asyncToolRunRegistry;
    private AgentExecutionProperties realExecutionProperties;
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
        realExecutionProperties = new AgentExecutionProperties();
        AgentExecutionProperties.React.Subagent sub = new AgentExecutionProperties.React.Subagent();
        sub.setEnabled(true);
        sub.setMaxIters(8);
        sub.setTimeoutMs(5_000L);
        realExecutionProperties.getReact().setSubagent(sub);
        asyncToolRunRegistry = new AsyncToolRunRegistry(realExecutionProperties);
        tool = new SpawnSubagentTool(
                executionProperties, timelineSupport, toolSetResolver,
                catalogHolder, spawnRunRegistry, agentCatalogService, agentExecutorRouter,
                asyncToolRunRegistry, skillCatalogService);
        registry = new StepEventBridgeRegistry();
        StepEventBridge.bindRegistry(registry);
        lenient().when(executionProperties.getReact()).thenReturn(reactProps);
        lenient().when(reactProps.getSubagent()).thenReturn(sub);
        lenient().when(reactProps.getAsyncTool()).thenReturn(realExecutionProperties.getReact().getAsyncTool());
        lenient().when(toolSetResolver.resolveDefaultTools(any(), any())).thenReturn(List.of("search_knowledge"));
        SpawnSubagentLabels.bind(new SpawnSubagentLabelService(timelinePromptCatalog));
        com.sunshine.orchestrator.processing.SkillLoadLabels.bind(new com.sunshine.orchestrator.processing.SkillLoadLabelService(
                skillCatalogService, com.sunshine.orchestrator.prompt.TimelinePromptCatalog.withDefaults()));    }

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
                "conv-1", MSG, "user-1", "default", null, null, null, null, null, null));

        when(agentExecutorRouter.dispatch(any(), any(), any(), any()))
                .thenReturn(Flux.just(StreamToken.content("hello")));

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
    void predefinedAgentWithSkill_injectsSkillLoadStep() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        registry.bind(BRIDGE, session, new ConcurrentLinkedQueue<>());
        StepEventBridge.bindHitlBridge(BRIDGE, MSG, true);
        StepEventBridge.registerMainRun(MSG, BRIDGE);
        StepEventBridge.bindToolAudit(MSG, new StepEventBridge.ToolAuditContext(
                "conv-1", MSG, "user-1", "default", null, null, null, null, null, null));

        when(agentCatalogService.find("compliance-agent"))
                .thenReturn(Optional.of(new com.sunshine.orchestrator.catalog.AgentCatalogEntry(
                        "compliance-agent", "业务合规对照智能体", "制度对照",
                        "system", List.of("compliance-check"), List.of(),
                        "[\"sdk__sunshine-biz__list_my_expenses\"]", true, "default",
                        null, null, null, null, 2, 5,
                        com.sunshine.orchestrator.catalog.AgentCatalogEntry.AgentSource.INTERNAL,
                        null, null, null, "chat", null)));
        when(skillCatalogService.findIndex("compliance-check"))
                .thenReturn(Optional.of(new com.sunshine.orchestrator.catalog.SkillCatalogIndexEntry(
                        "compliance-check", "业务合规检查", "desc", 1, true, "none", "all", null)));
        when(agentExecutorRouter.dispatch(any(), any(), any(), any()))
                .thenReturn(Flux.just(StreamToken.content("合规结论")));

        String out = tool.spawnSubagent("查我的报销", "compliance-agent", null);

        assertThat(out).isEqualTo("合规结论");
        ArgumentCaptor<StreamToken> tokenCaptor = ArgumentCaptor.forClass(StreamToken.class);
        verify(timelineSupport).fold(eq(BRIDGE), any(SpawnSubagentTimelineBridge.class), tokenCaptor.capture());
        ProcessingStep skillStep = tokenCaptor.getValue().step();
        assertThat(skillStep).isNotNull();
        assertThat(skillStep.id()).isEqualTo("skill");
        assertThat(skillStep.phase()).isEqualTo("skill");
        assertThat(skillStep.summary().after()).contains("compliance-check");
        assertThat(skillStep.metadata().skillId()).isEqualTo("compliance-check");
    }

    @Test
    void fluxStepTokens_doNotFoldAgain() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        registry.bind(BRIDGE, session, new ConcurrentLinkedQueue<>());
        StepEventBridge.bindHitlBridge(BRIDGE, MSG, true);
        StepEventBridge.registerMainRun(MSG, BRIDGE);
        StepEventBridge.bindToolAudit(MSG, new StepEventBridge.ToolAuditContext(
                "conv-1", MSG, "user-1", "default", null, null, null, null, null, null));

        when(agentExecutorRouter.dispatch(any(), any(), any(), any())).thenReturn(Flux.just(
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
                "conv-1", MSG, "user-1", "default", null, null, null, null, null, null));

        when(agentExecutorRouter.dispatch(any(), any(), any(), any())).thenAnswer(inv -> {
            com.sunshine.orchestrator.agent.runtime.AgentRunRequest req = inv.getArgument(1);
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

    @Test
    void backgroundTrue_returnsRunning_withoutBlockLast() throws Exception {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        registry.bind(BRIDGE, session, new ConcurrentLinkedQueue<>());
        StepEventBridge.bindHitlBridge(BRIDGE, MSG, true);
        StepEventBridge.registerMainRun(MSG, BRIDGE);
        StepEventBridge.bindToolAudit(MSG, new StepEventBridge.ToolAuditContext(
                "conv-1", MSG, "user-1", "default", null, null, null, null, null, null));

        CountDownLatch dispatchEntered = new CountDownLatch(1);
        CountDownLatch releaseFlux = new CountDownLatch(1);
        when(agentExecutorRouter.dispatch(any(), any(), any(), any())).thenReturn(
                Flux.<StreamToken>defer(() -> {
                    dispatchEntered.countDown();
                    return Flux.create(sink -> {
                        try {
                            if (!releaseFlux.await(10, TimeUnit.SECONDS)) {
                                sink.error(new IllegalStateException("release timeout"));
                                return;
                            }
                            sink.next(StreamToken.content("bg-answer"));
                            sink.complete();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            sink.error(e);
                        }
                    });
                }).subscribeOn(Schedulers.boundedElastic()));

        long started = System.currentTimeMillis();
        String out = tool.spawnSubagent("后台子任务", null, "后台卡", null, true);
        long elapsedMs = System.currentTimeMillis() - started;

        assertThat(elapsedMs).isLessThan(800L);
        assertThat(out).contains("\"ok\":true");
        assertThat(out).contains("\"status\":\"running\"");
        String runId = extractRunId(out);
        assertThat(runId).isNotBlank();
        assertThat(asyncToolRunRegistry.peek(runId)).isNotNull();
        assertThat(asyncToolRunRegistry.peek(runId).status())
                .isEqualTo(AsyncToolRunRegistry.Status.RUNNING);

        assertThat(dispatchEntered.await(3, TimeUnit.SECONDS)).isTrue();
        releaseFlux.countDown();
        assertThat(awaitCondition(() -> {
            var snap = asyncToolRunRegistry.peek(runId);
            return snap != null && snap.status() == AsyncToolRunRegistry.Status.DONE;
        }, 5_000)).isTrue();
        assertThat(asyncToolRunRegistry.peek(runId).result()).isEqualTo("bg-answer");
        verify(timelineSupport).complete(eq(BRIDGE), any(SpawnSubagentTimelineBridge.class), eq("bg-answer"));
        assertThat(awaitCondition(() -> spawnRunRegistry.get(runId) == null, 2_000)).isTrue();
    }

    @Test
    void background_userCancel_completesAsyncCancelled_withoutSuccessTimeline() throws Exception {
        bindMainSession();
        CountDownLatch dispatchEntered = new CountDownLatch(1);
        when(agentExecutorRouter.dispatch(any(), any(), any(), any())).thenReturn(
                Flux.<StreamToken>defer(() -> {
                    dispatchEntered.countDown();
                    return Flux.never();
                }).subscribeOn(Schedulers.boundedElastic()));

        String out = tool.spawnSubagent("取消后台子任务", null, "取消卡", null, true);
        assertThat(out).contains("\"status\":\"running\"");
        String runId = extractRunId(out);
        assertThat(dispatchEntered.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(spawnRunRegistry.cancel(runId)).isTrue();
        assertThat(awaitCondition(() -> {
            var snap = asyncToolRunRegistry.peek(runId);
            return snap != null && snap.status() == AsyncToolRunRegistry.Status.CANCELLED;
        }, 3_000)).isTrue();
        assertThat(asyncToolRunRegistry.peek(runId).result()).contains("用户已取消子任务");
        verify(timelineSupport, never()).complete(any(), any(), any());
        assertThat(awaitCondition(() -> spawnRunRegistry.get(runId) == null, 2_000)).isTrue();
    }

    @Test
    void background_wallTimeout_completesWallTimeout_withoutSuccessTimeline() throws Exception {
        AgentExecutionProperties.React.Subagent sub = new AgentExecutionProperties.React.Subagent();
        sub.setEnabled(true);
        sub.setMaxIters(8);
        sub.setTimeoutMs(200L);
        lenient().when(reactProps.getSubagent()).thenReturn(sub);

        bindMainSession();
        CountDownLatch dispatchEntered = new CountDownLatch(1);
        when(agentExecutorRouter.dispatch(any(), any(), any(), any())).thenReturn(
                Flux.<StreamToken>defer(() -> {
                    dispatchEntered.countDown();
                    return Flux.never();
                }).subscribeOn(Schedulers.boundedElastic()));

        String out = tool.spawnSubagent("墙钟超时子任务", null, "超时卡", null, true);
        assertThat(out).contains("\"status\":\"running\"");
        String runId = extractRunId(out);
        assertThat(dispatchEntered.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(awaitCondition(() -> {
            var snap = asyncToolRunRegistry.peek(runId);
            return snap != null && snap.status() == AsyncToolRunRegistry.Status.WALL_TIMEOUT;
        }, 3_000)).isTrue();
        verify(timelineSupport, never()).complete(any(), any(), any());
        assertThat(awaitCondition(() -> spawnRunRegistry.get(runId) == null, 2_000)).isTrue();
    }

    @Test
    void background_slotFull_rejectsAndUnregistersSpawn() {
        bindMainSession();
        assertThat(asyncToolRunRegistry.tryAcquireSlot(MSG)).isTrue();
        assertThat(asyncToolRunRegistry.tryAcquireSlot(MSG)).isTrue();
        assertThat(asyncToolRunRegistry.tryAcquireSlot(MSG)).isTrue();

        String out = tool.spawnSubagent("槽位已满", null, "拒卡", null, true);

        assertThat(out).contains("\"ok\":false");
        assertThat(out).contains("并发已达上限");
        ArgumentCaptor<String> runIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(timelineSupport).begin(eq(BRIDGE), runIdCaptor.capture(), eq("拒卡"), eq("槽位已满"));
        String runId = runIdCaptor.getValue();
        assertThat(spawnRunRegistry.get(runId)).isNull();
        assertThat(asyncToolRunRegistry.peek(runId)).isNull();
        verify(timelineSupport).fail(eq(BRIDGE), any(SpawnSubagentTimelineBridge.class), eq("本消息后台工具并发已达上限"));
        verify(agentExecutorRouter, never()).dispatch(any(), any(), any(), any());
    }

    private void bindMainSession() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        registry.bind(BRIDGE, session, new ConcurrentLinkedQueue<>());
        StepEventBridge.bindHitlBridge(BRIDGE, MSG, true);
        StepEventBridge.registerMainRun(MSG, BRIDGE);
        StepEventBridge.bindToolAudit(MSG, new StepEventBridge.ToolAuditContext(
                "conv-1", MSG, "user-1", "default", null, null, null, null, null, null));
    }

    private static String extractRunId(String json) {
        Matcher m = Pattern.compile("\"runId\":\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static boolean awaitCondition(java.util.function.BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        return condition.getAsBoolean();
    }
}
