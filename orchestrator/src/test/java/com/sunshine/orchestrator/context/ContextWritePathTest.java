package com.sunshine.orchestrator.context;

import com.sunshine.orchestrator.conversation.ConversationService;
import com.sunshine.orchestrator.conversation.entity.ChatConversationEntity;
import com.sunshine.orchestrator.conversation.entity.ChatMessageEntity;
import com.sunshine.orchestrator.context.l1.L1Compressor;
import com.sunshine.orchestrator.context.l2.L2ExtractService;
import com.sunshine.orchestrator.context.l3.L3IngestService;
import com.sunshine.orchestrator.biz.SceneWriteResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 写路由闸门（task-scene §2.1/§2.2）：kind 分流 scope + task×workflow 退出链路。
 */
@ExtendWith(MockitoExtension.class)
class ContextWritePathTest {

    @Mock
    private ConversationService conversationService;
    @Mock
    private L1Compressor l1Compressor;
    @Mock
    private L2ExtractService l2ExtractService;
    @Mock
    private L3IngestService l3IngestService;
    @Mock
    private SceneWriteResolver sceneWriteResolver;

    private ContextWritePath writePath;

    @BeforeEach
    void setUp() {
        writePath = new ContextWritePath(
                conversationService, l1Compressor, l2ExtractService, l3IngestService,
                new ContextWritePolicy(new ContextProperties()), sceneWriteResolver);
    }

    private ChatConversationEntity conv(String kind, String executionPreference) {
        ChatConversationEntity c = new ChatConversationEntity();
        c.setId("c1");
        c.setUserId("u1");
        c.setTenantId("default");
        c.setKind(kind);
        c.setWorkspaceId("ws-1");
        c.setExecutionPreference(executionPreference);
        return c;
    }

    private ChatMessageEntity msg(String id, String role, String content) {
        ChatMessageEntity m = new ChatMessageEntity();
        m.setId(id);
        m.setConversationId("c1");
        m.setRole(role);
        m.setContent(content);
        m.setStatus("completed");
        m.setCreatedAt(Instant.now());
        return m;
    }

    private void stubMessages(ChatConversationEntity conv) {
        ChatMessageEntity assistant = msg("m2", "assistant", "the answer");
        when(conversationService.getMessageOwned("m2", "u1", "default")).thenReturn(assistant);
        when(conversationService.getMessages("c1", "u1", "default"))
                .thenReturn(List.of(msg("m1", "user", "the question"), assistant));
        when(conversationService.getOwned("c1", "u1", "default")).thenReturn(conv);
        when(sceneWriteResolver.resolve(eq("u1"), eq("default"), eq("c1"), anyList()))
                .thenReturn(java.util.Optional.empty());
    }

    @Test
    void runAsync_taskWorkflow_skipsKvExtractAndL3Ingest() {
        stubMessages(conv("task", "workflow"));

        writePath.runAsync("m2", "u1", "default");

        // L1 消息折叠照常
        verify(l1Compressor).compress(eq("u1"), eq("default"), eq("c1"), anyList());
        // KV 抽取与 L3 向量化均跳过
        verify(l2ExtractService, never()).extractWorkspace(anyString(), anyString(), anyString(), anyList(), any());
        verify(l2ExtractService, never()).extract(anyString(), anyString(), anyString(), anyList(), any(), any());
        verify(l3IngestService, never())
                .ingestTurnPair(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void runAsync_taskFast_writesWorkspaceScopeAndL3() {
        stubMessages(conv("task", "fast"));

        writePath.runAsync("m2", "u1", "default");

        verify(l2ExtractService).extractWorkspace(eq("ws-1"), eq("default"), eq("m2"), anyList(), any());
        verify(l2ExtractService, never()).extract(anyString(), anyString(), anyString(), anyList(), any(), any());
        verify(l3IngestService)
                .ingestTurnPair(eq("u1"), eq("default"), eq("c1"), eq("task"),
                        eq("m1"), eq("the question"), eq("m2"), eq("the answer"), anyLong());
    }

    @Test
    void runAsync_chatWorkflow_notAffectedByTaskGate() {
        // workflow 裁剪仅作用于 task；chat×workflow 仍走 user scope + L3(scene=chat)
        stubMessages(conv("chat", "workflow"));

        writePath.runAsync("m2", "u1", "default");

        verify(l2ExtractService).extract(eq("u1"), eq("default"), eq("m2"), anyList(), any(), any());
        verify(l2ExtractService, never()).extractWorkspace(anyString(), anyString(), anyString(), anyList(), any());
        verify(l3IngestService)
                .ingestTurnPair(eq("u1"), eq("default"), eq("c1"), eq("chat"),
                        eq("m1"), eq("the question"), eq("m2"), eq("the answer"), anyLong());
    }
}
