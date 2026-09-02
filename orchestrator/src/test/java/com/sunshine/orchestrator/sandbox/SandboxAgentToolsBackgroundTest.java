package com.sunshine.orchestrator.sandbox;

import com.sunshine.common.sandbox.ToolInvokeResponse;
import com.sunshine.orchestrator.agent.AsyncToolRunRegistry;
import com.sunshine.orchestrator.agent.StepEventBridge;
import com.sunshine.orchestrator.audit.ToolAuditService;
import com.sunshine.orchestrator.client.SandboxClient;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.config.AgentSandboxProperties;
import com.sunshine.orchestrator.hitl.HitlConfirmationService;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SandboxAgentToolsBackgroundTest {

    private static final String BRIDGE = "main-bridge-bg";
    private static final String MSG = "msg-bg-exec";
    private static final String TOOL_USE_ID = "tu-bg-exec-1";
    private static final String SESSION_ID = "sess-bg-1";

    @Mock
    private SandboxClient sandboxClient;
    @Mock
    private HitlConfirmationService hitlConfirmationService;
    @Mock
    private ToolAuditService toolAuditService;
    @Mock
    private SandboxSessionLifecycle sandboxSessionLifecycle;
    @Mock
    private PromptCatalogHolder promptCatalogHolder;

    private AgentSandboxProperties sandboxProperties;
    private AgentExecutionProperties executionProperties;
    private AsyncToolRunRegistry asyncToolRunRegistry;
    private CancellableToolRunRegistry cancellableToolRunRegistry;
    private SandboxAgentTools tools;

    @BeforeEach
    void setUp() {
        sandboxProperties = new AgentSandboxProperties();
        executionProperties = new AgentExecutionProperties();
        // 槽位测试固定 3（生产默认已调至 10）
        executionProperties.getReact().getAsyncTool().setMaxConcurrentPerMessage(3);
        asyncToolRunRegistry = new AsyncToolRunRegistry(executionProperties);
        cancellableToolRunRegistry = new CancellableToolRunRegistry(sandboxClient, sandboxProperties);
        tools = new SandboxAgentTools(
                sandboxClient,
                hitlConfirmationService,
                toolAuditService,
                sandboxSessionLifecycle,
                sandboxProperties,
                cancellableToolRunRegistry,
                promptCatalogHolder,
                asyncToolRunRegistry,
                executionProperties);
        tools.init();

        ProcessingTimelineSession session = new ProcessingTimelineSession();
        StepEventBridge.bind(BRIDGE, session, new ConcurrentLinkedQueue<>());
        StepEventBridge.bindHitlBridge(BRIDGE, MSG, true);
        StepEventBridge.registerMainRun(MSG, BRIDGE);
        StepEventBridge.bindToolAudit(MSG, new StepEventBridge.ToolAuditContext(
                "conv-bg", MSG, "user-1", "default", null, null, null, null, null, null));
        SandboxSessionHolder.bind(BRIDGE, SESSION_ID, null);

        lenient().when(hitlConfirmationService.shouldConfirmForBridge(anyString(), anyString()))
                .thenReturn(false);
        lenient().when(sandboxSessionLifecycle.getCheckoutPath(anyString())).thenReturn(null);
    }

    @AfterEach
    void tearDown() {
        SandboxSessionHolder.clearAll();
        StepEventBridge.clear(BRIDGE);
        StepEventBridge.clear(MSG);
        StepEventBridge.clearForReactRestart(MSG);
    }

    @Test
    void backgroundTrue_returnsRunningJson_withoutBlockingInvoke() throws Exception {
        bindToolUse(TOOL_USE_ID);
        AtomicBoolean invokeEntered = new AtomicBoolean(false);
        when(sandboxClient.invoke(eq(SESSION_ID), eq("exec"), anyMap(), eq(TOOL_USE_ID)))
                .thenAnswer(inv -> {
                    invokeEntered.set(true);
                    Thread.sleep(2000);
                    return new ToolInvokeResponse(true, "bg-stdout", 0, Map.of());
                });

        long started = System.currentTimeMillis();
        ToolResultBlock result = callExec(TOOL_USE_ID, true, "sleep 2 && echo ok");
        long elapsedMs = System.currentTimeMillis() - started;

        assertThat(elapsedMs).isLessThan(500L);
        String text = resultText(result);
        assertThat(text).contains("\"ok\":true");
        assertThat(text).contains("\"runId\":\"" + TOOL_USE_ID + "\"");
        assertThat(text).contains("\"status\":\"running\"");
        assertThat(cancellableToolRunRegistry.get(TOOL_USE_ID)).isNotNull();

        assertThat(awaitCondition(invokeEntered::get, 3_000)).isTrue();
        verify(sandboxClient).invoke(eq(SESSION_ID), eq("exec"), anyMap(), eq(TOOL_USE_ID));

        assertThat(awaitCondition(() -> {
            var snap = asyncToolRunRegistry.peek(TOOL_USE_ID);
            return snap != null && snap.status() == AsyncToolRunRegistry.Status.DONE;
        }, 5_000)).isTrue();
        assertThat(asyncToolRunRegistry.peek(TOOL_USE_ID).result()).isEqualTo("bg-stdout");
        assertThat(awaitCondition(() -> cancellableToolRunRegistry.get(TOOL_USE_ID) == null, 2_000))
                .isTrue();
    }

    @Test
    void background_slotFull_rejectsJson_andUnregistersCancellable() {
        String toolUseId = "tu-bg-slot-full";
        bindToolUse(toolUseId);
        assertThat(asyncToolRunRegistry.tryAcquireSlot(MSG)).isTrue();
        assertThat(asyncToolRunRegistry.tryAcquireSlot(MSG)).isTrue();
        assertThat(asyncToolRunRegistry.tryAcquireSlot(MSG)).isTrue();

        ToolResultBlock result = callExec(toolUseId, true, "echo full");
        String text = resultText(result);
        assertThat(text).contains("\"ok\":false");
        assertThat(text).contains("并发已达上限");
        assertThat(cancellableToolRunRegistry.get(toolUseId)).isNull();
        assertThat(asyncToolRunRegistry.peek(toolUseId)).isNull();
    }

    @Test
    void background_registryCancel_killsSandboxInvocation() throws Exception {
        String toolUseId = "tu-bg-cancel-kill";
        bindToolUse(toolUseId);
        CountDownLatch invokeStarted = new CountDownLatch(1);
        when(sandboxClient.invoke(eq(SESSION_ID), eq("exec"), anyMap(), eq(toolUseId)))
                .thenAnswer(inv -> {
                    invokeStarted.countDown();
                    Thread.sleep(10_000);
                    return new ToolInvokeResponse(true, "late", 0, Map.of());
                });

        ToolResultBlock result = callExec(toolUseId, true, "sleep 99");
        assertThat(resultText(result)).contains("\"status\":\"running\"");
        assertThat(invokeStarted.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(asyncToolRunRegistry.cancel(toolUseId)).isTrue();
        verify(sandboxClient, timeout(2_000)).cancelInvocation(SESSION_ID, toolUseId);
        assertThat(awaitCondition(
                () -> asyncToolRunRegistry.peek(toolUseId).status()
                        == AsyncToolRunRegistry.Status.CANCELLED,
                2_000)).isTrue();
    }

    @Test
    void background_wallTimeout_killsSandboxInvocation() throws Exception {
        String toolUseId = "tu-bg-wall-kill";
        bindToolUse(toolUseId);
        executionProperties.getReact().getAsyncTool().setExecWallTimeoutSec(1);
        CountDownLatch invokeStarted = new CountDownLatch(1);
        when(sandboxClient.invoke(eq(SESSION_ID), eq("exec"), anyMap(), eq(toolUseId)))
                .thenAnswer(inv -> {
                    invokeStarted.countDown();
                    Thread.sleep(10_000);
                    return new ToolInvokeResponse(true, "late", 0, Map.of());
                });

        ToolResultBlock result = callExec(toolUseId, true, "sleep 99");
        assertThat(resultText(result)).contains("\"status\":\"running\"");
        assertThat(invokeStarted.await(3, TimeUnit.SECONDS)).isTrue();

        verify(sandboxClient, timeout(3_000).atLeastOnce()).cancelInvocation(SESSION_ID, toolUseId);
        assertThat(awaitCondition(
                () -> {
                    var snap = asyncToolRunRegistry.peek(toolUseId);
                    return snap != null && snap.status() == AsyncToolRunRegistry.Status.WALL_TIMEOUT;
                },
                3_000)).isTrue();
    }

    @Test
    void background_omittedTimeout_alignedToWallAndStripsBackgroundFlag() throws Exception {
        String toolUseId = "tu-bg-timeout-wall";
        bindToolUse(toolUseId);
        executionProperties.getReact().getAsyncTool().setExecWallTimeoutSec(600);
        when(sandboxClient.invoke(eq(SESSION_ID), eq("exec"), anyMap(), eq(toolUseId)))
                .thenReturn(new ToolInvokeResponse(true, "async-frontend-ok\n", 0, Map.of()));

        ToolResultBlock result = callExec(toolUseId, true, "sleep 45 && echo async-frontend-ok");
        assertThat(resultText(result)).contains("\"status\":\"running\"");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(sandboxClient, timeout(2_000)).invoke(
                eq(SESSION_ID), eq("exec"), bodyCaptor.capture(), eq(toolUseId));
        Map<String, Object> sent = bodyCaptor.getValue();
        assertThat(sent).doesNotContainKey("background");
        assertThat(sent.get("timeout_sec")).isEqualTo(600);
        assertThat(awaitCondition(() -> {
            var snap = asyncToolRunRegistry.peek(toolUseId);
            return snap != null && snap.status() == AsyncToolRunRegistry.Status.DONE;
        }, 3_000)).isTrue();
    }

    @Test
    void prepareBackgroundExecInvokeBody_keepsExplicitTimeout() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("command", "sleep 5");
        body.put("background", true);
        body.put("timeout_sec", 12);
        Map<String, Object> prepared = SandboxAgentTools.prepareBackgroundExecInvokeBody(body, 600);
        assertThat(prepared).doesNotContainKey("background");
        assertThat(prepared.get("timeout_sec")).isEqualTo(12);
    }

    @Test
    void backgroundFalse_stillBlocksOnInvoke() {
        String toolUseId = "tu-bg-sync";
        bindToolUse(toolUseId);
        when(sandboxClient.invoke(eq(SESSION_ID), eq("exec"), anyMap(), eq(toolUseId)))
                .thenReturn(new ToolInvokeResponse(true, "sync-out", 0, Map.of()));

        long started = System.currentTimeMillis();
        ToolResultBlock result = callExec(toolUseId, false, "echo sync");
        long elapsedMs = System.currentTimeMillis() - started;

        assertThat(elapsedMs).isLessThan(2_000L);
        assertThat(resultText(result)).isEqualTo("sync-out");
        assertThat(asyncToolRunRegistry.peek(toolUseId)).isNull();
        verify(sandboxClient, atLeastOnce()).invoke(eq(SESSION_ID), eq("exec"), anyMap(), eq(toolUseId));
    }

    private void bindToolUse(String toolUseId) {
        StepEventBridge.bindToolUseBridge(toolUseId, BRIDGE);
    }

    private ToolResultBlock callExec(String toolUseId, boolean background, String command) {
        AgentTool exec = findExecTool();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("command", command);
        input.put("background", background);
        ToolCallParam param = ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder()
                        .id(toolUseId)
                        .name(SandboxIds.EXEC)
                        .input(input)
                        .build())
                .input(input)
                .build();
        return exec.callAsync(param).block();
    }

    private AgentTool findExecTool() {
        return tools.all().stream()
                .filter(t -> SandboxIds.EXEC.equals(t.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("sandbox__exec missing"));
    }

    private static String resultText(ToolResultBlock result) {
        assertThat(result).isNotNull();
        assertThat(result.getOutput()).isNotEmpty();
        assertThat(result.getOutput().get(0)).isInstanceOf(TextBlock.class);
        return ((TextBlock) result.getOutput().get(0)).getText();
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
