package com.sunshine.orchestrator.context;

import com.sunshine.orchestrator.context.l1.ConversationContextL1Entity;
import com.sunshine.orchestrator.context.l1.ConversationContextL1Store;
import com.sunshine.orchestrator.context.l2.L2StateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContextAssemblerL1Test {

    @Mock
    private ConversationContextL1Store l1Store;
    @Mock
    private L2StateStore l2StateStore;

    private ContextProperties properties;
    private ContextAssembler assembler;

    @BeforeEach
    void setUp() {
        properties = new ContextProperties();
        properties.getL1().setNearTurns(2);
        properties.getL1().setMidTurns(2);
        properties.getL1().setMaxChars(100_000);
        assembler = new ContextAssembler(properties, l1Store, l2StateStore);
        lenient().when(l2StateStore.assembleSystemBlock(anyString(), anyString())).thenReturn("");
    }

    @Test
    void assemble_injectsL2SystemBlock() {
        when(l2StateStore.assembleSystemBlock("u1", "default"))
                .thenReturn("[用户状态 · L2]\n- preference/style: 简洁");
        when(l1Store.find("c1")).thenReturn(Optional.empty());
        when(l1Store.parseMidAnswers(null)).thenReturn(Map.of());
        when(l1Store.farSummaryOf(null)).thenReturn("");

        AssembledContext ctx = assembler.assemble(new ContextAssembler.AssembleRequest(
                "u1", "default", "c1", List.of(SessionTurn.of("user", "hi")), "q"));

        assertThat(ctx.l2SystemBlock()).contains("preference/style: 简洁");
    }

    @Test
    void midTurn_keepsFullUser_andSummarizedAssistant() {
        ConversationContextL1Entity entity = new ConversationContextL1Entity();
        entity.setConvId("c1");
        entity.setMidAnswers("{\"msg-a\":\"摘要A\"}");
        entity.setFarSummary("");
        when(l1Store.find("c1")).thenReturn(Optional.of(entity));
        when(l1Store.parseMidAnswers(entity)).thenReturn(Map.of("msg-a", "摘要A"));
        when(l1Store.farSummaryOf(entity)).thenReturn("");

        List<SessionTurn> history = List.of(
                SessionTurn.of("u-old", "user", "old Q"),
                SessionTurn.of("a-old", "assistant", "old A"),
                SessionTurn.of("u-mid", "user", "用户问题Q"),
                SessionTurn.of("msg-a", "assistant", "这是一段很长的助手原文答案……"),
                SessionTurn.of("u-near", "user", "近窗问"),
                SessionTurn.of("a-near", "assistant", "近窗答"));

        AssembledContext ctx = assembler.assemble(new ContextAssembler.AssembleRequest(
                "u1", "default", "c1", history, "current"));

        assertThat(ctx.nearTurns()).hasSize(2);
        assertThat(ctx.nearTurns().get(0).content()).isEqualTo("近窗问");
        assertThat(ctx.midTurns()).hasSize(2);
        assertThat(ctx.midTurns().get(0).role()).isEqualTo("user");
        assertThat(ctx.midTurns().get(0).content()).isEqualTo("用户问题Q");
        assertThat(ctx.midTurns().get(1).role()).isEqualTo("assistant");
        assertThat(ctx.midTurns().get(1).content()).isEqualTo("摘要A");
    }

    @Test
    void assemble_injectsFarSummaryBlock() {
        ConversationContextL1Entity entity = new ConversationContextL1Entity();
        entity.setConvId("c1");
        entity.setFarSummary("早期对话：用户问了 A，助手答了 B。");
        when(l1Store.find("c1")).thenReturn(Optional.of(entity));
        when(l1Store.parseMidAnswers(entity)).thenReturn(Map.of());
        when(l1Store.farSummaryOf(entity)).thenReturn("早期对话：用户问了 A，助手答了 B。");

        List<SessionTurn> history = List.of(
                SessionTurn.of("u0", "user", "Q0"),
                SessionTurn.of("a0", "assistant", "A0"),
                SessionTurn.of("u1", "user", "Q1"),
                SessionTurn.of("a1", "assistant", "A1"),
                SessionTurn.of("u2", "user", "Q2"),
                SessionTurn.of("a2", "assistant", "A2"));

        AssembledContext ctx = assembler.assemble(new ContextAssembler.AssembleRequest(
                "u1", "default", "c1", history, "q"));

        assertThat(ctx.farSummaryBlock()).isEqualTo("早期对话：用户问了 A，助手答了 B。");
        assertThat(ctx.midTurns()).hasSize(2);
        assertThat(ctx.nearTurns()).hasSize(2);
    }

    @Test
    void assemble_midFallbackToFullWhenNoSummary() {
        when(l1Store.find("c1")).thenReturn(Optional.empty());
        when(l1Store.parseMidAnswers(null)).thenReturn(Map.of());
        when(l1Store.farSummaryOf(null)).thenReturn("");

        List<SessionTurn> history = List.of(
                SessionTurn.of("u0", "user", "Q0"),
                SessionTurn.of("a0", "assistant", "A0 full"),
                SessionTurn.of("u1", "user", "Q1"),
                SessionTurn.of("a1", "assistant", "A1"));

        AssembledContext ctx = assembler.assemble(new ContextAssembler.AssembleRequest(
                "u1", "default", "c1", history, "q"));

        assertThat(ctx.midTurns()).hasSize(2);
        assertThat(ctx.midTurns().get(1).content()).isEqualTo("A0 full");
        assertThat(ctx.farSummaryBlock()).isBlank();
    }
}
