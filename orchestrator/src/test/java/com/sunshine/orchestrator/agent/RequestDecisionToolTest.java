package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.TimelineLabelJUnitExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, TimelineLabelJUnitExtension.class})
class RequestDecisionToolTest {

    private static final String BRIDGE = "main-bridge";
    private static final String MSG = "msg-decision-tool";
    private static final String QUESTIONS_OK = """
            [{"id":"q1","prompt":"用哪种模式？","options":[{"id":"agent","label":"Agent"},{"id":"plan","label":"Plan"}]}]
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
    void formats_answered_result_with_multi_question() {
        var answers = List.of(
                new DecisionAnswer("q1", List.of("agent"), null),
                new DecisionAnswer("q2", List.of("perf", DecisionOption.CUSTOM_ID), "安全"));
        String text = RequestDecisionTool.formatSuccessResult("Need", answers);
        assertThat(text).contains("outcome=answered");
        assertThat(text).contains("title=Need");
        assertThat(text).contains("q.q1=agent");
        assertThat(text).contains("q.q2=perf,__custom__");
        assertThat(text).contains("q.q2.custom=安全");
    }

    @Test
    void disabled_returnsErrorJson_withoutCard() {
        decisionCfg.setEnabled(false);
        String out = tool.requestDecision("确认", QUESTIONS_OK);
        assertThat(out).contains("\"ok\":false");
        assertThat(out).contains("未启用");
        verify(timelineSupport, never()).begin(
                anyString(), anyString(), anyString(), anyList(), anyLong());
        verify(decisionRegistry, never()).register(anyString(), anyString(), anyString(), anyList());
    }

    @Test
    void questionsEmpty_returnsErrorJson() {
        String out = tool.requestDecision("T", "[]");
        assertThat(out).contains("\"ok\":false");
        assertThat(out).contains("questions");
        verify(timelineSupport, never()).begin(
                anyString(), anyString(), anyString(), anyList(), anyLong());
    }

    @Test
    void optionsFewerThan2_returnsErrorJson() {
        String out = tool.requestDecision(
                "T",
                "[{\"id\":\"q1\",\"prompt\":\"选？\",\"options\":[{\"id\":\"only\",\"label\":\"唯一\"}]}]");
        assertThat(out).contains("\"ok\":false");
        assertThat(out).contains("options");
        verify(timelineSupport, never()).begin(
                anyString(), anyString(), anyString(), anyList(), anyLong());
    }

    @Test
    void duplicateQuestionId_returnsErrorJson() {
        String out = tool.requestDecision(
                "T",
                """
                [{"id":"q1","prompt":"一","options":[{"id":"a","label":"A"},{"id":"b","label":"B"}]},
                 {"id":"q1","prompt":"二","options":[{"id":"c","label":"C"},{"id":"d","label":"D"}]}]
                """);
        assertThat(out).contains("\"ok\":false");
        assertThat(out).contains("id");
        verify(timelineSupport, never()).begin(
                anyString(), anyString(), anyString(), anyList(), anyLong());
    }

    @Test
    void subBridge_returnsErrorJson() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        registry.bind("sub-agent-1", session, new ConcurrentLinkedQueue<>());
        StepEventBridge.bindHitlBridge("sub-agent-1", MSG, true);
        StepEventBridge.registerMainRun(MSG, BRIDGE);

        String out = tool.requestDecision("确认", QUESTIONS_OK);
        assertThat(out).contains("\"ok\":false");
        assertThat(out).contains("子 Agent");
        verify(timelineSupport, never()).begin(
                anyString(), anyString(), anyString(), anyList(), anyLong());
    }

    @Test
    void parses_questions_native_list() throws Exception {
        bindMainContext();
        DecisionRegistry.Registration reg = new DecisionRegistry.Registration(
                "tok-list", new CompletableFuture<>(), System.currentTimeMillis() + 300_000L, "T");
        when(decisionRegistry.hasAwaiting(MSG)).thenReturn(false);
        when(decisionRegistry.register(eq(MSG), anyString(), eq("T"), anyList())).thenReturn(reg);
        when(decisionRegistry.awaitDecision(reg))
                .thenReturn(new DecisionResult(
                        "answered",
                        "T",
                        List.of(new DecisionAnswer("q1", List.of("agent"), null)),
                        System.currentTimeMillis()));

        List<Map<String, Object>> questionsList = List.of(questionMap(
                "q1",
                "用哪种模式？",
                List.of(optionMap("agent", "Agent"), optionMap("plan", "Plan")),
                false));

        String out = tool.requestDecision("T", questionsList);

        assertThat(out).contains("outcome=answered");
        assertThat(out).contains("q.q1=agent");
        ArgumentCaptor<List<DecisionQuestion>> questionsCaptor = ArgumentCaptor.forClass(List.class);
        verify(decisionRegistry).register(eq(MSG), anyString(), eq("T"), questionsCaptor.capture());
        DecisionQuestion q = questionsCaptor.getValue().get(0);
        assertThat(q.id()).isEqualTo("q1");
        assertThat(q.options()).hasSize(2);
        assertThat(q.options().get(0).id()).isEqualTo("agent");
        assertThat(q.options().get(0).label()).isEqualTo("Agent");
        verify(timelineSupport).begin(eq(BRIDGE), eq("tok-list"), eq("T"), anyList(), anyLong());
        verify(timelineSupport).complete(eq(BRIDGE), eq("tok-list"), any(DecisionResult.class));
    }

    @Test
    void success_formatsShortResult() throws Exception {
        bindMainContext();
        DecisionRegistry.Registration reg = new DecisionRegistry.Registration(
                "tok-1", new CompletableFuture<>(), System.currentTimeMillis() + 300_000L, "确认");
        when(decisionRegistry.hasAwaiting(MSG)).thenReturn(false);
        when(decisionRegistry.register(eq(MSG), anyString(), eq("确认"), anyList())).thenReturn(reg);
        when(decisionRegistry.awaitDecision(reg))
                .thenReturn(new DecisionResult(
                        "answered",
                        "确认",
                        List.of(new DecisionAnswer("q1", List.of("agent"), null)),
                        System.currentTimeMillis()));

        String out = tool.requestDecision("确认", QUESTIONS_OK);

        assertThat(out).contains("outcome=answered");
        assertThat(out).contains("title=确认");
        assertThat(out).contains("q.q1=agent");
        verify(timelineSupport).complete(eq(BRIDGE), eq("tok-1"), any(DecisionResult.class));
    }

    @Test
    void timeout_formatsOutcomeTimeout() throws Exception {
        bindMainContext();
        DecisionRegistry.Registration reg = new DecisionRegistry.Registration(
                "tok-to", new CompletableFuture<>(), System.currentTimeMillis() + 300_000L, "确认");
        when(decisionRegistry.hasAwaiting(MSG)).thenReturn(false);
        when(decisionRegistry.register(eq(MSG), anyString(), eq("确认"), anyList())).thenReturn(reg);
        when(decisionRegistry.awaitDecision(reg))
                .thenReturn(new DecisionResult("timeout", "确认", List.of(), System.currentTimeMillis()));
        when(decisionRegistry.timeoutSec()).thenReturn(300);

        String out = tool.requestDecision("确认", QUESTIONS_OK);

        assertThat(out).isEqualTo("outcome=timeout\ntimeoutSec=300");
        verify(timelineSupport).pause(eq(BRIDGE), eq("tok-to"), any());
    }

    @Test
    void preApproval_returnsShortFormat_withoutRegister() {
        bindMainContext();
        List<DecisionQuestion> questions = List.of(new DecisionQuestion(
                "q1",
                "用哪种模式？",
                List.of(new DecisionOption("agent", "Agent"), new DecisionOption("plan", "Plan")),
                false));
        String fingerprint = DecisionFingerprint.of("确认", questions);
        StepEventBridge.grantDecisionPreApproval(
                MSG,
                fingerprint,
                new DecisionResult(
                        "answered",
                        "确认",
                        List.of(new DecisionAnswer("q1", List.of("agent"), null)),
                        1L));

        String out = tool.requestDecision("确认", QUESTIONS_OK);

        assertThat(out).contains("outcome=answered");
        assertThat(out).contains("q.q1=agent");
        verify(decisionRegistry, never()).register(anyString(), anyString(), anyString(), anyList());
        verify(timelineSupport, never()).begin(
                anyString(), anyString(), anyString(), anyList(), anyLong());
    }

    private void bindMainContext() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        registry.bind(BRIDGE, session, new ConcurrentLinkedQueue<>());
        StepEventBridge.bindHitlBridge(BRIDGE, MSG, true);
        StepEventBridge.registerMainRun(MSG, BRIDGE);
        StepEventBridge.bindToolAudit(MSG, new StepEventBridge.ToolAuditContext(
                "conv-1", MSG, "user-1", "default", null, null, null, null, null));
    }

    private static Map<String, Object> questionMap(
            String id, String prompt, List<Map<String, Object>> options, boolean allowMultiple) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("prompt", prompt);
        map.put("options", options);
        map.put("allowMultiple", allowMultiple);
        return map;
    }

    private static Map<String, Object> optionMap(String id, String label) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("label", label);
        return map;
    }
}
