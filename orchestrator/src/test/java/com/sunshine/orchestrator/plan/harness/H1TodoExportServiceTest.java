package com.sunshine.orchestrator.plan.harness;

import com.sunshine.orchestrator.context.l2.L2ConflictMerger;
import com.sunshine.orchestrator.context.l2.L2StateStore;
import com.sunshine.orchestrator.conversation.entity.ChatConversationEntity;
import com.sunshine.orchestrator.conversation.repo.ChatConversationRepository;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class H1TodoExportServiceTest {

    @Mock
    private L2StateStore l2StateStore;
    @Mock
    private ChatConversationRepository conversationRepo;

    @InjectMocks
    private H1TodoExportService service;

    private static ExecutionStreamContext ctx(String kind, String convId) {
        return new ExecutionStreamContext(
                convId, "msg-1", "用户查询", null, null, null,
                "u1", "default", null, null, null, null, null, false, false, null, null, kind);
    }

    @Test
    void export_taskConversation_persistsWorkspaceScopeTodo() {
        PlanNotebook notebook = notebook("task", "部署 QA-2026-0817 环境");
        notebook.upsertTask(TaskItem.initial("t1", "部署 QA 环境", List.of(), null, null, null));
        notebook.upsertTask(TaskItem.initial("t2", "回归测试", List.of(), null, null, null)
                .withStatus("done", null));
        ChatConversationEntity conv = new ChatConversationEntity();
        conv.setId("conv-1");
        conv.setWorkspaceId("ws-1");
        when(conversationRepo.findById("conv-1")).thenReturn(Optional.of(conv));

        service.export(notebook, ctx("task", "conv-1"));

        ArgumentCaptor<L2ConflictMerger.Candidate> cap =
                ArgumentCaptor.forClass(L2ConflictMerger.Candidate.class);
        verify(l2StateStore).syncTodoExportWorkspace(
                org.mockito.ArgumentMatchers.eq("ws-1"),
                org.mockito.ArgumentMatchers.eq("default"),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.eq("msg-1"),
                any(Instant.class));
        verify(l2StateStore).syncTodoExportWorkspace(
                org.mockito.ArgumentMatchers.eq("ws-1"),
                org.mockito.ArgumentMatchers.eq("default"),
                org.mockito.ArgumentMatchers.argThat(list -> {
                    if (list == null || list.size() != 1) {
                        return false;
                    }
                    L2ConflictMerger.Candidate c = list.get(0);
                    return "todo".equals(c.kind())
                            && c.key().equals("task." + H1TodoExportService.goalHash("部署 QA-2026-0817 环境") + ".t1")
                            && "部署 QA 环境".equals(c.value())
                            && "部署 QA-2026-0817 环境".equals(c.background())
                            && "active".equals(c.status());
                }),
                org.mockito.ArgumentMatchers.eq("msg-1"),
                any(Instant.class));
    }

    @Test
    void export_chatConversation_persistsUserScopeTodo() {
        PlanNotebook notebook = notebook("chat", "整理周报");
        notebook.upsertTask(TaskItem.initial("t1", "收集本周数据", List.of(), null, null, null));

        service.export(notebook, ctx("chat", "conv-chat"));

        verify(l2StateStore).syncTodoExport(
                org.mockito.ArgumentMatchers.eq("u1"),
                org.mockito.ArgumentMatchers.eq("default"),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.eq("msg-1"),
                any(Instant.class));
        verify(l2StateStore, never()).syncTodoExportWorkspace(
                any(), any(), any(), any(), any(Instant.class));
    }

    @Test
    void export_onlyPendingInProgressFail() {
        PlanNotebook notebook = notebook("task", "目标");
        notebook.upsertTask(TaskItem.initial("t1", "a", List.of(), null, null, null));
        notebook.upsertTask(TaskItem.initial("t2", "b", List.of(), null, null, null)
                .withStatus("in_progress", null));
        notebook.upsertTask(TaskItem.initial("t3", "c", List.of(), null, null, null)
                .withStatus("fail", "超时"));
        notebook.upsertTask(TaskItem.initial("t4", "d", List.of(), null, null, null)
                .withStatus("done", null));
        notebook.upsertTask(TaskItem.initial("t5", "e", List.of(), null, null, null)
                .withStatus("cancelled", null));
        notebook.upsertTask(TaskItem.initial("t6", "f", List.of(), null, null, null)
                .withStatus("obsolete", null));
        when(conversationRepo.findById("conv-1")).thenReturn(Optional.of(conv("ws-1")));

        service.export(notebook, ctx("task", "conv-1"));

        verify(l2StateStore).syncTodoExportWorkspace(
                org.mockito.ArgumentMatchers.eq("ws-1"),
                org.mockito.ArgumentMatchers.eq("default"),
                org.mockito.ArgumentMatchers.argThat(list -> list != null && list.size() == 3),
                org.mockito.ArgumentMatchers.eq("msg-1"),
                any(Instant.class));
    }

    @Test
    void export_taskWithoutWorkspace_skips() {
        PlanNotebook notebook = notebook("task", "目标");
        notebook.upsertTask(TaskItem.initial("t1", "a", List.of(), null, null, null));
        when(conversationRepo.findById("conv-x")).thenReturn(Optional.empty());

        service.export(notebook, ctx("task", "conv-x"));

        verifyNoInteractions(l2StateStore);
    }

    @Test
    void export_emptyQueue_exportEmptyList() {
        PlanNotebook notebook = notebook("task", "目标");
        when(conversationRepo.findById("conv-1")).thenReturn(Optional.of(conv("ws-1")));

        service.export(notebook, ctx("task", "conv-1"));

        verify(l2StateStore).syncTodoExportWorkspace(
                org.mockito.ArgumentMatchers.eq("ws-1"),
                org.mockito.ArgumentMatchers.eq("default"),
                org.mockito.ArgumentMatchers.argThat(list -> list != null && list.isEmpty()),
                org.mockito.ArgumentMatchers.eq("msg-1"),
                any(Instant.class));
    }

    @Test
    void goalHash_isStableAndScoped() {
        String g1 = "部署 QA-2026-0817 环境";
        String g2 = "整理周报";
        assertThat(H1TodoExportService.goalHash(g1))
                .isEqualTo(H1TodoExportService.goalHash(g1));
        assertThat(H1TodoExportService.goalHash(g1)).isNotEqualTo(H1TodoExportService.goalHash(g2));
        assertThat(H1TodoExportService.goalHash(g1)).matches("^[0-9a-f]{8}$");
        assertThat(H1TodoExportService.goalHash("")).isEqualTo("00000000");
    }

    private static PlanNotebook notebook(String kind, String goal) {
        return PlanNotebook.create(goal, goal, kind, 10, 20);
    }

    private static ChatConversationEntity conv(String workspaceId) {
        ChatConversationEntity c = new ChatConversationEntity();
        c.setId("conv-1");
        c.setWorkspaceId(workspaceId);
        return c;
    }
}
