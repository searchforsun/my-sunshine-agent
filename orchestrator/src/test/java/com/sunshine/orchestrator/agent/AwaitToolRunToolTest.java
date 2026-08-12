package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.TimelineLabelJUnitExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, TimelineLabelJUnitExtension.class})
class AwaitToolRunToolTest {

    private static final String BRIDGE = "main-bridge";
    private static final String MSG = "msg-await-tool";
    private static final String RUN_ID = "run-1";

    @Mock
    private AgentExecutionProperties executionProperties;
    @Mock
    private AgentExecutionProperties.React reactProps;
    @Mock
    private AsyncToolRunRegistry asyncRegistry;

    private AgentExecutionProperties.React.AsyncTool asyncCfg;
    private AwaitToolRunTool tool;
    private StepEventBridgeRegistry registry;

    @BeforeEach
    void setUp() {
        asyncCfg = new AgentExecutionProperties.React.AsyncTool();
        asyncCfg.setEnabled(true);
        lenient().when(executionProperties.getReact()).thenReturn(reactProps);
        lenient().when(reactProps.getAsyncTool()).thenReturn(asyncCfg);
        tool = new AwaitToolRunTool(executionProperties, asyncRegistry);
        registry = new StepEventBridgeRegistry();
        StepEventBridge.bindRegistry(registry);
    }

    @AfterEach
    void tearDown() {
        registry.clearAll();
        StepEventBridge.resetRegistry();
    }

    @Test
    void NAME_equals_await_tool_run() {
        assertThat(AwaitToolRunTool.NAME).isEqualTo("await_tool_run");
    }

    @Test
    void disabled_returnsErrorJson() {
        asyncCfg.setEnabled(false);
        String out = tool.awaitToolRun(RUN_ID, 30, "tu-1");
        assertThat(out).contains("\"ok\":false");
        assertThat(out).contains("未启用");
    }

    @Test
    void unknownRun_returnsErrorJson() {
        bindMainContext();
        when(asyncRegistry.await(eq(RUN_ID), anyInt())).thenReturn(null);

        String out = tool.awaitToolRun(RUN_ID, 30, "tu-1");

        assertThat(out).contains("\"ok\":false");
        assertThat(out).contains("未知 runId");
    }

    @Test
    void passesRequestedTimeout_registryClampsByKind() {
        bindMainContext();
        var snapshot = new AsyncToolRunRegistry.Snapshot(
                RUN_ID,
                AsyncToolRunRegistry.Kind.SANDBOX_EXEC,
                AsyncToolRunRegistry.Status.RUNNING,
                1,
                3,
                12_345L,
                null,
                "partial-output",
                null);
        when(asyncRegistry.await(eq(RUN_ID), eq(999))).thenReturn(snapshot);

        String out = tool.awaitToolRun(RUN_ID, 999, "tu-1");

        assertThat(out).contains("\"ok\":true");
        assertThat(out).contains("\"runId\":\"run-1\"");
        assertThat(out).contains("\"status\":\"running\"");
        assertThat(out).contains("\"waitCount\":1");
        assertThat(out).contains("\"waitBudget\":3");
        assertThat(out).contains("\"elapsedMs\":12345");
        assertThat(out).contains("\"partial\":\"partial-output\"");
        assertThat(out).doesNotContain("\"result\"");
    }

    @Test
    void nullTimeout_passesZeroForKindDefault() {
        bindMainContext();
        var snapshot = new AsyncToolRunRegistry.Snapshot(
                RUN_ID,
                AsyncToolRunRegistry.Kind.SPAWN_SUBAGENT,
                AsyncToolRunRegistry.Status.RUNNING,
                1,
                3,
                100L,
                null,
                null,
                null);
        when(asyncRegistry.await(eq(RUN_ID), eq(0))).thenReturn(snapshot);

        tool.awaitToolRun(RUN_ID, null, "tu-1");

        verify(asyncRegistry).await(eq(RUN_ID), eq(0));
    }

    @Test
    void nonPositiveTimeoutSec_clampsToOne_notDefault() {
        bindMainContext();
        var snapshot = new AsyncToolRunRegistry.Snapshot(
                RUN_ID,
                AsyncToolRunRegistry.Kind.SANDBOX_EXEC,
                AsyncToolRunRegistry.Status.RUNNING,
                1,
                3,
                100L,
                null,
                null,
                null);
        when(asyncRegistry.await(eq(RUN_ID), eq(1))).thenReturn(snapshot);

        tool.awaitToolRun(RUN_ID, 0, "tu-1");
        tool.awaitToolRun(RUN_ID, -5, "tu-1");

        verify(asyncRegistry, times(2)).await(eq(RUN_ID), eq(1));
    }

    @Test
    void subBridge_returnsErrorJson() {
        bindMainContext();
        ProcessingTimelineSession sub = new ProcessingTimelineSession();
        registry.bind("sub-agent-1", sub, new ConcurrentLinkedQueue<>());
        StepEventBridge.bindHitlBridge("sub-agent-1", MSG, true);
        String toolUseId = "tu-from-sub";
        StepEventBridge.bindToolUseBridge(toolUseId, "sub-agent-1");

        String out = tool.awaitToolRun(RUN_ID, 30, toolUseId);

        assertThat(out).contains("\"ok\":false");
        assertThat(out).contains("子 Agent");
    }

    private void bindMainContext() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        registry.bind(BRIDGE, session, new ConcurrentLinkedQueue<>());
        StepEventBridge.bindHitlBridge(BRIDGE, MSG, true);
        StepEventBridge.registerMainRun(MSG, BRIDGE);
        StepEventBridge.bindToolAudit(MSG, new StepEventBridge.ToolAuditContext(
                "conv-1", MSG, "user-1", "default", null, null, null, null, null));
    }
}
