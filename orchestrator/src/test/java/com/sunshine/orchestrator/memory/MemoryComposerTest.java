package com.sunshine.orchestrator.memory;

import com.sunshine.orchestrator.conversation.ChatTurn;
import com.sunshine.orchestrator.memory.ltm.LtmProfileService;
import com.sunshine.orchestrator.memory.mtm.MtmService;
import com.sunshine.orchestrator.memory.stm.StmStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryComposerTest {

    @Mock
    private LtmProfileService ltmProfileService;
    @Mock
    private MtmService mtmService;
    @Mock
    private StmStore stmStore;

    private MemoryProperties memoryProperties;
    private MemoryComposer composer;

    @BeforeEach
    void setUp() {
        memoryProperties = new MemoryProperties();
        composer = new MemoryComposer(memoryProperties, ltmProfileService, mtmService);
        ReflectionTestUtils.setField(composer, "stmStore", stmStore);
        when(ltmProfileService.buildSnippet(any(), any())).thenReturn(Optional.empty());
        when(ltmProfileService.ensureProfile(any(), any())).thenReturn(null);
        when(mtmService.recallSnippet(any(), any(), any(), any())).thenReturn(Optional.empty());
    }

    @Test
    void compose_includesFullStmTurnsWithoutCurrentUser() {
        List<ChatTurn> loaded = List.of(
                new ChatTurn("user", "写 cpp 快排"),
                new ChatTurn("assistant", "cpp code full content"));
        MemoryContext ctx = composer.compose(new MemoryComposer.ComposeRequest(
                "u1", "default", "c1", loaded, "写 py 快排"));
        assertThat(ctx.stmTurns()).hasSize(2);
        assertThat(ctx.stmTurns().get(0).content()).isEqualTo("写 cpp 快排");
        assertThat(ctx.stmTurns().get(1).content()).isEqualTo("cpp code full content");
    }

    @Test
    void compose_stmTurns_notTruncatedPerMessage() {
        String longReply = "x".repeat(2000);
        List<ChatTurn> loaded = List.of(
                new ChatTurn("user", "q"),
                new ChatTurn("assistant", longReply));
        MemoryContext ctx = composer.compose(new MemoryComposer.ComposeRequest(
                "u1", "default", "c1", loaded, "follow up"));
        assertThat(ctx.stmTurns().get(1).content()).hasSize(2000);
    }

    @Test
    void compose_prefersDbOverStaleRedisCacheMissingAssistant() {
        List<ChatTurn> fromDb = List.of(
                new ChatTurn("user", "写 cpp 快排"),
                new ChatTurn("assistant", "完整 cpp 快排代码"));

        MemoryContext ctx = composer.compose(new MemoryComposer.ComposeRequest(
                "u1", "default", "c1", fromDb, "再写 py 快排"));

        assertThat(ctx.stmTurns()).hasSize(2);
        assertThat(ctx.stmTurns().get(1).content()).isEqualTo("完整 cpp 快排代码");
        verify(stmStore, never()).load(eq("u1"), eq("c1"));
    }

    @Test
    void compose_filtersBlankAssistantFromDb() {
        List<ChatTurn> fromDb = List.of(
                new ChatTurn("user", "hello"),
                new ChatTurn("assistant", "  "));

        MemoryContext ctx = composer.compose(new MemoryComposer.ComposeRequest(
                "u1", "default", "c1", fromDb, "again"));

        assertThat(ctx.stmTurns()).hasSize(1);
        assertThat(ctx.stmTurns().get(0).role()).isEqualTo("user");
    }

    @Test
    void compose_includesLtmAndMtmSnippets() {
        when(ltmProfileService.buildSnippet(eq("u1"), eq("default")))
                .thenReturn(Optional.of("[用户画像 · LTM] 部门=财务"));
        when(mtmService.recallSnippet(eq("u1"), eq("default"), eq("报销流程"), eq("c1")))
                .thenReturn(Optional.of("[相关历史情景 · MTM]\n- 上周问过报销"));

        MemoryContext ctx = composer.compose(new MemoryComposer.ComposeRequest(
                "u1", "default", "c1", List.of(), "报销流程"));

        assertThat(ctx.ltmSnippet()).contains("LTM");
        assertThat(ctx.mtmSnippet()).contains("MTM");
    }
}
