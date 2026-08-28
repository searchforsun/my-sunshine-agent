package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.context.ContextProperties;
import com.sunshine.orchestrator.context.l3.HistoryRagClient;
import com.sunshine.orchestrator.conversation.entity.ChatConversationEntity;
import com.sunshine.orchestrator.conversation.repo.ChatConversationRepository;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionSearchToolTest {

    @Mock
    private HistoryRagClient historyRagClient;
    @Mock
    private ContextProperties contextProperties;
    @Mock
    private ChatConversationRepository conversationRepo;

    private SessionSearchTool tool;

    @BeforeEach
    void setUp() {
        ContextProperties.L3 l3 = new ContextProperties.L3();
        l3.setTopK(3);
        org.mockito.Mockito.lenient().when(contextProperties.getL3()).thenReturn(l3);
        AgentExecutionProperties.React.SessionSearch sessionSearch = new AgentExecutionProperties.React.SessionSearch();
        sessionSearch.setWorkspaceMaxConvs(2);
        AgentExecutionProperties.React react = new AgentExecutionProperties.React();
        react.setSessionSearch(sessionSearch);
        AgentExecutionProperties props = new AgentExecutionProperties();
        props.setReact(react);
        tool = new SessionSearchTool(historyRagClient, contextProperties, conversationRepo, props);
    }

    private static StepEventBridge.ToolAuditContext audit(String convId) {
        return new StepEventBridge.ToolAuditContext(
                convId, "msg-1", "u1", "default", null, null, null, null, null, "task");
    }

    private static ToolCallParam param(String query, String scope) {
        Map<String, Object> input = new java.util.LinkedHashMap<>();
        input.put("query", query);
        if (scope != null) {
            input.put("scope", scope);
        }
        return ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder().id("call-1").name(SessionSearchTool.NAME).input(input).build())
                .input(input)
                .build();
    }

    private static ChatConversationEntity conv(String id, String workspaceId) {
        ChatConversationEntity entity = new ChatConversationEntity();
        entity.setId(id);
        entity.setWorkspaceId(workspaceId);
        return entity;
    }

    private static String toolText(ToolResultBlock block) {
        if (block == null || block.getOutput() == null || block.getOutput().isEmpty()) {
            return "";
        }
        Object first = block.getOutput().get(0);
        return first instanceof TextBlock t ? t.getText() : "";
    }

    @Test
    void search_sessionScope_passesConversationIdAndFormatsHits() {
        when(historyRagClient.search(
                eq("u1"), eq("default"), eq("conv-9"), eq("task"),
                eq(List.of("body", "process")), anyString(), eq(3)))
                .thenReturn(Mono.just(List.of(
                        new HistoryRagClient.HistoryHit("conv-9", "m1", "早前约定验收清单三步", 0.9f, 1000L),
                        new HistoryRagClient.HistoryHit("conv-9", "m2", "冒烟测试通过", 0.8f, 2000L))));

        ToolResultBlock block = tool.execute(param("之前验收步骤是什么", null), audit("conv-9"));

        assertThat(toolText(block)).contains("早前约定验收清单三步").contains("冒烟测试通过");
        verify(historyRagClient).search(eq("u1"), eq("default"), eq("conv-9"), eq("task"),
                eq(List.of("body", "process")), anyString(), eq(3));
    }

    @Test
    void search_workspaceScope_expandsWorkspaceConversationsAndSearches() {
        when(conversationRepo.findById("conv-ws-9")).thenReturn(Optional.of(conv("conv-ws-9", "ws-1")));
        when(conversationRepo.findTaskIdsByWorkspace("u1", "default", "ws-1"))
                .thenReturn(List.of("conv-a", "conv-ws-9", "conv-b"));
        when(historyRagClient.search(eq("u1"), eq("default"), eq(List.of("conv-a", "conv-b")), eq("task"),
                eq(List.of("body", "process")), anyString(), eq(3)))
                .thenReturn(Mono.just(List.of(
                        new HistoryRagClient.HistoryHit("conv-a", "m1", "另一会话的进度记录", 0.9f, 1000L))));

        ToolResultBlock block = tool.execute(param("之前任务的进度", "workspace"), audit("conv-ws-9"));

        // 当前会话被排除，仅检索工作区其他 task 会话
        assertThat(toolText(block)).contains("另一会话的进度记录");
        verify(historyRagClient).search(eq("u1"), eq("default"), eq(List.of("conv-a", "conv-b")), eq("task"),
                eq(List.of("body", "process")), anyString(), eq(3));
    }

    @Test
    void search_workspaceScope_noWorkspace_returnsError() {
        when(conversationRepo.findById("conv-9")).thenReturn(Optional.of(conv("conv-9", null)));

        ToolResultBlock block = tool.execute(param("之前的约定", "workspace"), audit("conv-9"));

        assertThat(toolText(block)).contains("未绑定工作区");
        verify(historyRagClient, never()).search(
                anyString(), anyString(), anyList(), anyString(), anyList(), anyString(), anyInt());
    }

    @Test
    void search_workspaceScope_noOtherConversations_returnsMessage() {
        when(conversationRepo.findById("conv-9")).thenReturn(Optional.of(conv("conv-9", "ws-1")));
        when(conversationRepo.findTaskIdsByWorkspace("u1", "default", "ws-1"))
                .thenReturn(List.of("conv-9"));

        ToolResultBlock block = tool.execute(param("之前的约定", "workspace"), audit("conv-9"));

        assertThat(toolText(block)).contains("工作区暂无其他任务会话历史");
        verify(historyRagClient, never()).search(
                anyString(), anyString(), anyList(), anyString(), anyList(), anyString(), anyInt());
    }

    @Test
    void search_workspaceScope_truncatesToMaxConvs() {
        when(conversationRepo.findById("conv-9")).thenReturn(Optional.of(conv("conv-9", "ws-1")));
        when(conversationRepo.findTaskIdsByWorkspace("u1", "default", "ws-1"))
                .thenReturn(List.of("conv-a", "conv-b", "conv-c", "conv-d"));
        when(historyRagClient.search(eq("u1"), eq("default"), eq(List.of("conv-a", "conv-b")), eq("task"),
                eq(List.of("body", "process")), anyString(), eq(3)))
                .thenReturn(Mono.just(List.of()));

        ToolResultBlock block = tool.execute(param("之前的约定", "workspace"), audit("conv-9"));

        // workspaceMaxConvs=2：仅取前 2 个会话
        assertThat(toolText(block)).contains("工作区其他任务会话未检索到");
        verify(historyRagClient).search(eq("u1"), eq("default"), eq(List.of("conv-a", "conv-b")), eq("task"),
                eq(List.of("body", "process")), anyString(), eq(3));
    }

    @Test
    void search_withoutConversationId_returnsError() {
        StepEventBridge.ToolAuditContext noConv = audit(null);

        ToolResultBlock block = tool.execute(param("之前的约定", null), noConv);

        assertThat(toolText(block)).contains("缺少会话上下文");
        verify(historyRagClient, never()).search(anyString(), anyString(), anyString(), anyString(), anyList(), anyString(), anyInt());
    }

    @Test
    void search_nullAudit_returnsError() {
        ToolResultBlock block = tool.execute(param("之前的约定", null), null);

        assertThat(toolText(block)).contains("缺少会话上下文");
    }

    @Test
    void search_blankQuery_returnsError() {
        ToolResultBlock block = tool.execute(param("  ", null), audit("conv-9"));

        assertThat(toolText(block)).contains("query 不能为空");
        verify(historyRagClient, never()).search(anyString(), anyString(), anyString(), anyString(), anyList(), anyString(), anyInt());
    }

    @Test
    void search_invalidScope_returnsError() {
        ToolResultBlock block = tool.execute(param("之前的约定", "global"), audit("conv-9"));

        assertThat(toolText(block)).contains("scope 仅支持 session");
        verify(historyRagClient, never()).search(anyString(), anyString(), anyString(), anyString(), anyList(), anyString(), anyInt());
    }

    @Test
    void search_emptyHits_returnsNoRecordMessage() {
        when(historyRagClient.search(eq("u1"), eq("default"), eq("conv-9"), eq("task"),
                eq(List.of("body", "process")), anyString(), eq(3)))
                .thenReturn(Mono.just(List.of()));

        ToolResultBlock block = tool.execute(param("不存在的内容", null), audit("conv-9"));

        assertThat(toolText(block)).contains("未检索到");
    }

    @Test
    void search_clientError_returnsFailureMessage() {
        when(historyRagClient.search(eq("u1"), eq("default"), eq("conv-9"), eq("task"),
                eq(List.of("body", "process")), anyString(), eq(3)))
                .thenReturn(Mono.error(new RuntimeException("timeout")));

        ToolResultBlock block = tool.execute(param("之前的约定", null), audit("conv-9"));

        assertThat(toolText(block)).contains("工具调用失败");
    }
}
