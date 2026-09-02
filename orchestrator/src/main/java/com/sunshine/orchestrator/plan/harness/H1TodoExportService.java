package com.sunshine.orchestrator.plan.harness;

import com.sunshine.orchestrator.context.l2.ContextKind;
import com.sunshine.orchestrator.context.l2.L2ConflictMerger;
import com.sunshine.orchestrator.context.l2.L2StateStore;
import com.sunshine.orchestrator.conversation.entity.ChatConversationEntity;
import com.sunshine.orchestrator.conversation.repo.ChatConversationRepository;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * M2 pro 终态导出：Planner-Executor 会话收束时，将 H1 {@link PlanNotebook} taskQueue 的
 * 未完成项（pending / in_progress / fail）结构性导出到 KV Memory（kind=todo）。
 * <p>
 * key 编码 {@code task.{goalHash8}.{baseTaskId}}：goalHash 由 originalGoal SHA-256 前 8 hex
 * 派生，同一 goal 跨会话复用同前缀；baseTaskId 去版本后缀。落点按会话 kind 分流：
 * task → workspace 维度（workspaceId 经 conversation 反查）、chat → user 维度。
 * 幂等由 {@link L2StateStore#syncTodoExport} 全量对比保证（不在本次集合的 task.* 前缀 active 行 void）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class H1TodoExportService {

    private static final String KEY_PREFIX = "task.";
    private static final Set<String> UNFINISHED = Set.of("pending", "in_progress", "fail");

    private final L2StateStore l2StateStore;
    private final ChatConversationRepository conversationRepo;

    public void export(PlanNotebook notebook, ExecutionStreamContext ctx) {
        if (notebook == null || ctx == null) {
            return;
        }
        String goal = resolveGoal(notebook);
        if (!StringUtils.hasText(goal)) {
            return;
        }
        String goalHash = goalHash(goal);
        List<L2ConflictMerger.Candidate> pending = notebook.snapshotQueue().stream()
                .filter(t -> t != null && UNFINISHED.contains(t.status()))
                .filter(t -> StringUtils.hasText(t.label()))
                .map(t -> new L2ConflictMerger.Candidate(
                        ContextKind.TODO.wire(),
                        KEY_PREFIX + goalHash + "." + TaskItem.stripRetrySuffix(t.taskId()),
                        t.label(),
                        1.0,
                        goal,
                        "active"))
                .toList();
        Instant now = Instant.now();
        if ("task".equals(ctx.conversationKind())) {
            String workspaceId = resolveWorkspaceId(ctx);
            if (!StringUtils.hasText(workspaceId)) {
                log.warn("[H1TodoExport] task 会话无 workspaceId，跳过导出 conversation={}",
                        ctx.conversationId());
                return;
            }
            l2StateStore.syncTodoExportWorkspace(
                    workspaceId, ctx.tenantId(), pending, ctx.assistantMsgId(), now);
        } else {
            l2StateStore.syncTodoExport(ctx.userId(), ctx.tenantId(), pending, ctx.assistantMsgId(), now);
        }
        log.info("[H1TodoExport] exported kind={} unfinished={} goalHash={}",
                ctx.conversationKind(), pending.size(), goalHash);
    }

    private String resolveGoal(PlanNotebook notebook) {
        if (StringUtils.hasText(notebook.getOriginalGoal())) {
            return notebook.getOriginalGoal().strip();
        }
        if (StringUtils.hasText(notebook.getUserQuery())) {
            return notebook.getUserQuery().strip();
        }
        return "";
    }

    private String resolveWorkspaceId(ExecutionStreamContext ctx) {
        if (!StringUtils.hasText(ctx.conversationId())) {
            return null;
        }
        try {
            ChatConversationEntity conv = conversationRepo.findById(ctx.conversationId()).orElse(null);
            return conv != null ? conv.getWorkspaceId() : null;
        } catch (Exception e) {
            log.warn("[H1TodoExport] conversation 反查失败 conv={}: {}", ctx.conversationId(), e.getMessage());
            return null;
        }
    }

    /** originalGoal → SHA-256 前 8 hex：同一 goal 跨部署稳定。 */
    static String goalHash(String goal) {
        if (!StringUtils.hasText(goal)) {
            return "00000000";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(goal.strip().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", digest[i] & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(goal.hashCode());
        }
    }
}
