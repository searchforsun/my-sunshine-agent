package com.sunshine.orchestrator.sandbox;

import com.sunshine.common.sandbox.ToolInvokeResponse;
import com.sunshine.orchestrator.client.SandboxClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceCheckoutService {

    private final WorkspaceSandboxStore store;
    private final WorkspaceSandboxLifecycle lifecycle;
    private final SandboxClient sandboxClient;
    private final StringRedisTemplate redis;

    private static final String LOCK_PREFIX = "sandbox:ws:lock:";

    public record CheckoutInfo(String branch, String path, boolean isMain, List<String> conversationIds) {}
    public record MergeResult(boolean success, List<String> conflictFiles) {}

    public String createWorktree(String workspaceId, String userId, String tenantId,
                                  String branch, String fromRef) {
        String sessionId = lifecycle.ensureWorkspaceSession(workspaceId, userId, tenantId);
        String lockKey = LOCK_PREFIX + workspaceId;
        if (Boolean.FALSE.equals(redis.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(30)))) {
            throw new IllegalStateException("工作区 git 操作繁忙，请稍后重试");
        }
        try {
            String ref = StringUtils.hasText(fromRef) ? fromRef.strip() : "HEAD";
            String cmd = "git -C /workspace/main worktree add /workspace/branches/" + branch
                    + " -b " + branch + " " + ref;
            ToolInvokeResponse resp = sandboxClient.invoke(sessionId, "exec",
                    Map.of("command", cmd, "cwd", "/workspace/main"));
            if (!resp.ok()) {
                throw new IllegalStateException("worktree create failed: " + resp.output());
            }
            return "/workspace/branches/" + branch;
        } finally {
            redis.delete(lockKey);
        }
    }

    public List<CheckoutInfo> listCheckouts(String workspaceId, String userId, String tenantId) {
        String sessionId = lifecycle.ensureWorkspaceSession(workspaceId, userId, tenantId);
        ToolInvokeResponse resp = sandboxClient.invoke(sessionId, "exec",
                Map.of("command", "git -C /workspace/main worktree list --porcelain", "cwd", "/workspace/main"));
        List<CheckoutInfo> result = new ArrayList<>();
        result.add(new CheckoutInfo("main", "/workspace/main", true, List.of()));
        return result;
    }

    public MergeResult mergeToMain(String workspaceId, String userId, String tenantId, String branch) {
        String sessionId = lifecycle.ensureWorkspaceSession(workspaceId, userId, tenantId);
        String lockKey = LOCK_PREFIX + workspaceId;
        if (Boolean.FALSE.equals(redis.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(30)))) {
            throw new IllegalStateException("工作区 git 操作繁忙，请稍后重试");
        }
        try {
            ToolInvokeResponse resp = sandboxClient.invoke(sessionId, "exec",
                    Map.of("command", "git -C /workspace/main merge " + branch, "cwd", "/workspace/main"));
            if (resp.ok()) {
                return new MergeResult(true, List.of());
            }
            ToolInvokeResponse conflictResp = sandboxClient.invoke(sessionId, "exec",
                    Map.of("command", "git -C /workspace/main diff --name-only --diff-filter=U",
                           "cwd", "/workspace/main"));
            List<String> conflicts = conflictResp.output() != null
                    ? conflictResp.output().lines().map(String::strip).filter(s -> !s.isEmpty()).toList()
                    : List.of();
            return new MergeResult(false, conflicts);
        } finally {
            redis.delete(lockKey);
        }
    }

    public void removeWorktree(String workspaceId, String userId, String tenantId, String branch) {
        String sessionId = lifecycle.ensureWorkspaceSession(workspaceId, userId, tenantId);
        String lockKey = LOCK_PREFIX + workspaceId;
        if (Boolean.FALSE.equals(redis.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(30)))) {
            throw new IllegalStateException("工作区 git 操作繁忙，请稍后重试");
        }
        try {
            sandboxClient.invoke(sessionId, "exec",
                    Map.of("command",
                            "git -C /workspace/main worktree remove /workspace/branches/" + branch + " --force"
                                    + " && git -C /workspace/main branch -D " + branch,
                            "cwd", "/workspace/main"));
        } finally {
            redis.delete(lockKey);
        }
    }
}
