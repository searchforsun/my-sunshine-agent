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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
        StepEventBridge.bindToolUseBridge(TOOL_USE_ID, BRIDGE);
        StepEventBridge.bindToolAudit(MSG, new StepEventBridge.ToolAuditContext(
                "conv-bg", MSG, "user-1", "default", null, null, null, null, null));
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
        AtomicBoolean invokeEntered = new AtomicBoolean(false);
        when(sandboxClient.invoke(eq(SESSION_ID), eq("exec"), anyMap(), eq(TOOL_USE_ID)))
                .thenAnswer(inv -> {
                    invokeEntered.set(true);
                    Thread.sleep(2000);
                    return new ToolInvokeResponse(true, "bg-stdout", 0, Map.of());
                });

        AgentTool exec = findExecTool();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("command", "sleep 2 && echo ok");
        input.put("background", true);
        ToolCallParam param = ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder()
                        .id(TOOL_USE_ID)
                        .name(SandboxIds.EXEC)
                        .input(input)
                        .build())
                .input(input)
                .build();

        long started = System.currentTimeMillis();
        ToolResultBlock result = exec.callAsync(param).block();
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
