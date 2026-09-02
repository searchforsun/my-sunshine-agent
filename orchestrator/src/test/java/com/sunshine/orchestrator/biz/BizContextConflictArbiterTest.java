package com.sunshine.orchestrator.biz;

import com.sunshine.orchestrator.audit.AuditEvent;
import com.sunshine.orchestrator.audit.AuditPublisher;
import com.sunshine.orchestrator.client.LlmGatewayClient;
import com.sunshine.orchestrator.prompt.PromptCatalogEntry;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.prompt.PromptCatalogSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * business-context M4 冲突仲裁（authority §5.2）单测：
 * 闸门 / 权威参照缺失放行 · LLM 冲突过滤 · 无冲突放行 · 失败兜底（drop/keep）+ 审计。
 */
class BizContextConflictArbiterTest {

    private static final String TEMPLATE =
            "你是业务上下文冲突仲裁器。\n\n=== USER ===\n\n"
            + "【业务红线 · 场景 {scene}】\n{policy}\n\n【任务板现状】\n{taskBoard}\n\n【历史材料】\n{l3}";

    private BusinessContextProperties properties;
    private BusinessContextAssembler businessContextAssembler;
    private LlmGatewayClient llmGatewayClient;
    private PromptCatalogHolder catalogHolder;
    private AuditPublisher auditPublisher;
    private BizContextConflictArbiter arbiter;

    @BeforeEach
    void setUp() {
        properties = new BusinessContextProperties();
        businessContextAssembler = mock(BusinessContextAssembler.class);
        llmGatewayClient = mock(LlmGatewayClient.class);
        catalogHolder = mock(PromptCatalogHolder.class);
        auditPublisher = mock(AuditPublisher.class);
        arbiter = new BizContextConflictArbiter(
                properties, businessContextAssembler, llmGatewayClient, catalogHolder,
                auditPublisher, new ObjectMapper());
        when(catalogHolder.snapshot()).thenReturn(PromptCatalogSnapshot.of(0, List.of(
                new PromptCatalogEntry("context.biz-scene.conflict-check", "context", "conflict",
                        true, 0, 1, TEMPLATE, null))));
    }

    private void enable(boolean policy, boolean taskBoard) {
        properties.getConflictCheck().setEnabled(true);
        when(businessContextAssembler.renderPolicyBlock(anyString(), anyString(), any()))
                .thenReturn(policy ? "差旅住宿标准上限 500 元/晚" : "");
        when(businessContextAssembler.renderTaskBlock(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(taskBoard ? "当前焦点任务：差旅报销（pending）" : "");
    }

    @Test
    void gateOff_returnsNull() {
        properties.getConflictCheck().setEnabled(false);
        String result = arbiter.arbitrate("t1", "u1", "travel-budget", "c1", "m1", "历史材料若干");
        assertThat(result).isNull();
        verify(auditPublisher, never()).publish(any());
    }

    @Test
    void blankScene_returnsNull() {
        enable(true, true);
        String result = arbiter.arbitrate("t1", "u1", "", "c1", "m1", "历史材料若干");
        assertThat(result).isNull();
    }

    @Test
    void blankL3_returnsNull() {
        enable(true, true);
        String result = arbiter.arbitrate("t1", "u1", "travel-budget", "c1", "m1", "   ");
        assertThat(result).isNull();
    }

    @Test
    void noAuthorityReference_returnsNull() {
        enable(false, false);
        String result = arbiter.arbitrate("t1", "u1", "travel-budget", "c1", "m1", "历史材料若干");
        assertThat(result).isNull();
        verify(llmGatewayClient, never()).complete(anyString(), anyString());
    }

    @Test
    void conflictSnippet_filtered() throws Exception {
        enable(true, true);
        when(llmGatewayClient.complete(anyString(), anyString()))
                .thenReturn("{\"filter\":[{\"snippet\":\"该笔报销已审批通过\",\"reason\":\"与任务板 pending 矛盾\"}]}");
        String l3 = "段落一：该笔报销已审批通过，钱已到账。\n\n段落二：上周还查过差旅标准。";
        String result = arbiter.arbitrate("t1", "u1", "travel-budget", "c1", "m1", l3);
        assertThat(result).isNotNull();
        assertThat(result).doesNotContain("该笔报销已审批通过");
        assertThat(result).contains("段落二");
        verify(auditPublisher).publish(any());
    }

    @Test
    void noConflict_returnsNull() throws Exception {
        enable(true, true);
        when(llmGatewayClient.complete(anyString(), anyString())).thenReturn("{\"filter\":[]}");
        String result = arbiter.arbitrate("t1", "u1", "travel-budget", "c1", "m1", "普通历史段落");
        assertThat(result).isNull();
        verify(auditPublisher, never()).publish(any());
    }

    @Test
    void llmFailure_defaultDropL3() {
        enable(true, true);
        when(llmGatewayClient.complete(anyString(), anyString())).thenThrow(new RuntimeException("llm down"));
        String result = arbiter.arbitrate("t1", "u1", "travel-budget", "c1", "m1", "历史材料若干");
        assertThat(result).isEqualTo("");
        verify(auditPublisher).publish(any());
    }

    @Test
    void llmFailure_keepPolicy_returnsNull() {
        enable(true, true);
        properties.getConflictCheck().setLlmFailurePolicy("keep");
        when(llmGatewayClient.complete(anyString(), anyString())).thenThrow(new RuntimeException("llm down"));
        String result = arbiter.arbitrate("t1", "u1", "travel-budget", "c1", "m1", "历史材料若干");
        assertThat(result).isNull();
    }

    @Test
    void unparsableOutput_dropL3() throws Exception {
        enable(true, true);
        when(llmGatewayClient.complete(anyString(), anyString())).thenReturn("抱歉我无法判断");
        String result = arbiter.arbitrate("t1", "u1", "travel-budget", "c1", "m1", "历史材料若干");
        assertThat(result).isEqualTo("");
    }

    @Test
    void auditEvent_hasConflictType() {
        enable(true, true);
        when(llmGatewayClient.complete(anyString(), anyString()))
                .thenReturn("{\"filter\":[{\"snippet\":\"已审批通过\",\"reason\":\"矛盾\"}]}");
        arbiter.arbitrate("t1", "u1", "travel-budget", "c1", "m1", "第一段已审批通过\n\n第二段");
        verify(auditPublisher).publish(any());
        org.mockito.ArgumentCaptor<AuditEvent> captor =
                org.mockito.ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo("BIZ_CONTEXT_CONFLICT");
        assertThat(captor.getValue().status()).isEqualTo("filtered");
    }
}
