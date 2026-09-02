package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.processing.TimelineLabelJUnitExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, TimelineLabelJUnitExtension.class})
class AsyncStatusToolTest {

    private static final String RUN_ID = "run-1";

    @Mock
    private AgentExecutionProperties executionProperties;
    @Mock
    private AgentExecutionProperties.React reactProps;
    @Mock
    private AsyncToolRunRegistry asyncRegistry;

    private AgentExecutionProperties.React.AsyncTool asyncCfg;
    private AsyncStatusTool tool;

    @BeforeEach
    void setUp() {
        asyncCfg = new AgentExecutionProperties.React.AsyncTool();
        asyncCfg.setEnabled(true);
        lenient().when(executionProperties.getReact()).thenReturn(reactProps);
        lenient().when(reactProps.getAsyncTool()).thenReturn(asyncCfg);
        tool = new AsyncStatusTool(asyncRegistry);
    }

    @Test
    void NAME_equals_async_status() {
        assertThat(AsyncStatusTool.NAME).isEqualTo("async_status");
    }

    @Test
    void single_peek_formatsSnapshotWithKind() {
        var snapshot = new AsyncToolRunRegistry.Snapshot(
                RUN_ID,
                AsyncToolRunRegistry.Kind.SANDBOX_EXEC,
                AsyncToolRunRegistry.Status.RUNNING,
                1,
                3,
                12_345L,
                null,
                "partial-output");
        when(asyncRegistry.peek(eq(RUN_ID))).thenReturn(snapshot);

        String out = tool.queryStatus(RUN_ID);

        assertThat(out).contains("\"ok\":true");
        assertThat(out).contains("\"runId\":\"run-1\"");
        assertThat(out).contains("\"kind\":\"sandbox_exec\"");
        assertThat(out).contains("\"status\":\"running\"");
        assertThat(out).contains("\"elapsedMs\":12345");
        assertThat(out).contains("\"partial\":\"partial-output\"");
        verify(asyncRegistry).peek(eq(RUN_ID));
    }

    @Test
    void single_done_includesResult() {
        var snapshot = new AsyncToolRunRegistry.Snapshot(
                RUN_ID,
                AsyncToolRunRegistry.Kind.WORKER_DISPATCH,
                AsyncToolRunRegistry.Status.DONE,
                2,
                6,
                200L,
                "handoff-ok",
                null);
        when(asyncRegistry.peek(eq(RUN_ID))).thenReturn(snapshot);

        String out = tool.queryStatus(RUN_ID);

        assertThat(out).contains("\"ok\":true");
        assertThat(out).contains("\"status\":\"done\"");
        assertThat(out).contains("\"kind\":\"worker_dispatch\"");
        assertThat(out).contains("\"result\":\"handoff-ok\"");
    }

    @Test
    void batch_peek_skipsUnknownRuns() {
        var s1 = new AsyncToolRunRegistry.Snapshot(
                "r1", AsyncToolRunRegistry.Kind.SPAWN_SUBAGENT,
                AsyncToolRunRegistry.Status.DONE, 1, 3, 100L, "done-1", null);
        when(asyncRegistry.peek(eq("r1"))).thenReturn(s1);
        when(asyncRegistry.peek(eq("unknown-2"))).thenReturn(null);

        String out = tool.queryStatus(List.of("r1", "unknown-2"));

        assertThat(out).contains("\"ok\":true");
        assertThat(out).contains("\"runs\"");
        assertThat(out).contains("\"runId\":\"r1\"");
        assertThat(out).doesNotContain("unknown-2");
    }

    @Test
    void allUnknown_returnsErrorJson() {
        when(asyncRegistry.peek(eq("unknown"))).thenReturn(null);

        String out = tool.queryStatus(List.of("unknown"));

        assertThat(out).contains("\"ok\":false");
        assertThat(out).contains("未知 runId");
    }

    @Test
    void emptyOrNull_returnsErrorJson() {
        assertThat(tool.queryStatus(List.of())).contains("\"ok\":false");
        assertThat(tool.queryStatus((List<String>) null)).contains("\"ok\":false");
    }
}
