package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.agent.runtime.AgentRuntime;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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

    private SpawnSubagentTool tool;
    private StepEventBridgeRegistry registry;

    @BeforeEach
    void setUp() {
        tool = new SpawnSubagentTool(agentRuntime, executionProperties, timelineSupport);
        registry = new StepEventBridgeRegistry();
        StepEventBridge.bindRegistry(registry);
        AgentExecutionProperties.React.Subagent sub = new AgentExecutionProperties.React.Subagent();
        sub.setEnabled(true);
        sub.setMaxIters(8);
        sub.setTimeoutMs(5_000L);
        lenient().when(executionProperties.getReact()).thenReturn(reactProps);
        lenient().when(reactProps.getSubagent()).thenReturn(sub);
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
        String out = tool.spawnSubagent("  ", null);
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
                "conv-1", MSG, "user-1", "default", null, null));

        when(agentRuntime.run(any())).thenReturn(Flux.just(StreamToken.content("hello")));

        String out = tool.spawnSubagent("请完成子任务", "制度检索");

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
}
