package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequestDecisionToolTest {

    private static final String BRIDGE = "main-bridge";
    private static final String MSG = "msg-decision-tool";
    private static final String OPTIONS_OK = """
            [{"value":"plan_a","label":"方案A","description":"稳妥"},{"value":"plan_b","label":"方案B"}]
            """;

    @Mock
    private AgentExecutionProperties executionProperties;
    @Mock
    private AgentExecutionProperties.React reactProps;
    @Mock
    private DecisionRegistry decisionRegistry;
    @Mock
    private DecisionTimelineSupport timelineSupport;

    private AgentExecutionProperties.React.Decision decisionCfg;
    private RequestDecisionTool tool;
    private StepEventBridgeRegistry registry;

    @BeforeEach
    void setUp() {
        decisionCfg = new AgentExecutionProperties.React.Decision();
        decisionCfg.setEnabled(true);
        decisionCfg.setTimeoutSec(300);
        lenient().when(executionProperties.getReact()).thenReturn(reactProps);
        lenient().when(reactProps.getDecision()).thenReturn(decisionCfg);
        tool = new RequestDecisionTool(executionProperties, decisionRegistry, timelineSupport);
        registry = new StepEventBridgeRegistry();
        StepEventBridge.bindRegistry(registry);
    }

    @AfterEach
    void tearDown() {
        registry.clearAll();
        StepEventBridge.resetRegistry();
    }

    @Test
    void NAME_equals_request_decision() {
        assertThat(RequestDecisionTool.NAME).isEqualTo("request_decision");
    }

    @Test
    void disabled_returnsErrorJson_withoutCard() {
        decisionCfg.setEnabled(false);
        String out = tool.requestDecision("请选择方案", OPTIONS_OK, false);
        assertThat(out).contains("\"ok\":false");
        assertThat(out).contains("未启用");
        verify(timelineSupport, never()).begin(
                anyString(), anyString(), anyString(), anyList(), anyBoolean(), anyLong());
        verify(decisionRegistry, never()).register(
                anyString(), anyString(), anyString(), anyList(), anyBoolean());
    }

    @Test
    void optionsFewerThan2_returnsErrorJson() {
        String out = tool.requestDecision(
                "请选择方案",
                "[{\"value\":\"only\",\"label\":\"唯一\"}]",
                false);
        assertThat(out).contains("\"ok\":false");
        assertThat(out).contains("options");
        verify(timelineSupport, never()).begin(
                anyString(), anyString(), anyString(), anyList(), anyBoolean(), anyLong());
    }

    @Test
    void duplicateValue_returnsErrorJson() {
        String out = tool.requestDecision(
                "请选择方案",
                "[{\"value\":\"dup\",\"label\":\"甲\"},{\"value\":\"dup\",\"label\":\"乙\"}]",
                false);
        assertThat(out).contains("\"ok\":false");
        assertThat(out).contains("value");
        verify(timelineSupport, never()).begin(
                anyString(), anyString(), anyString(), anyList(), anyBoolean(), anyLong());
    }

    @Test
    void subBridge_returnsErrorJson() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        registry.bind("sub-agent-1", session, new ConcurrentLinkedQueue<>());
        StepEventBridge.bindHitlBridge("sub-agent-1", MSG, true);
        StepEventBridge.registerMainRun(MSG, BRIDGE);

        String out = tool.requestDecision("请选择方案", OPTIONS_OK, false);
        assertThat(out).contains("\"ok\":false");
        assertThat(out).contains("子 Agent");
        verify(timelineSupport, never()).begin(
                anyString(), anyString(), anyString(), anyList(), anyBoolean(), anyLong());
    }

    @Test
    void success_formatsShortResult() throws Exception {
        bindMainContext();
        DecisionRegistry.Registration reg = new DecisionRegistry.Registration(
                "tok-1", new CompletableFuture<>(), System.currentTimeMillis() + 300_000L);
        when(decisionRegistry.hasAwaiting(MSG)).thenReturn(false);
        when(decisionRegistry.register(eq(MSG), anyString(), anyString(), anyList(), eq(false)))
                .thenReturn(reg);
        when(decisionRegistry.awaitDecision(reg))
                .thenReturn(new DecisionResult("plan_a", null, System.currentTimeMillis()));

        String out = tool.requestDecision("请选择方案", OPTIONS_OK, false);

        assertThat(out).contains("choice=plan_a");
        assertThat(out).contains("label=方案A");
        assertThat(out).contains("customInput=");
        ArgumentCaptor<List<DecisionOption>> optionsCaptor = ArgumentCaptor.forClass(List.class);
        verify(timelineSupport).begin(
                eq(BRIDGE), eq("tok-1"), eq("请选择方案"), optionsCaptor.capture(), eq(false), anyLong());
        assertThat(optionsCaptor.getValue()).hasSize(2);
        verify(timelineSupport).complete(
                eq(BRIDGE), eq("tok-1"), any(DecisionResult.class), eq("方案A"));
    }

    @Test
    void preApproval_returnsShortFormat_withoutRegister() {
        bindMainContext();
        List<DecisionOption> options = List.of(
                new DecisionOption("plan_a", "方案A", "稳妥", false),
                new DecisionOption("plan_b", "方案B", null, false));
        String fingerprint = DecisionFingerprint.of("请选择方案", options);
        StepEventBridge.grantDecisionPreApproval(
                MSG, fingerprint, new DecisionResult("plan_a", "自定义补充", 1L));

        String out = tool.requestDecision("请选择方案", OPTIONS_OK, false);

        assertThat(out).isEqualTo("choice=plan_a\nlabel=方案A\ncustomInput=自定义补充");
        verify(decisionRegistry, never()).register(
                anyString(), anyString(), anyString(), anyList(), anyBoolean());
        verify(timelineSupport, never()).begin(
                anyString(), anyString(), anyString(), anyList(), anyBoolean(), anyLong());
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
