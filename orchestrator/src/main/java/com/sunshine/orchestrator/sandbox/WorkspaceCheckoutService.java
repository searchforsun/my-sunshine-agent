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
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceCheckoutService {

    /** 容器内共享裸镜像库路径：宿主 repos/{wsId}.git 挂载到 /opt/git（PathJail 仅放行 /workspace、/skills，AI 文件工具读不到） */
    private static final String REPO_PATH = "/opt/git";
    /** exec 工具 cwd 必须是 jail 内路径；git 命令经 -C 指向 /opt/git */
    private static final String EXEC_CWD = "/workspace";

    private final WorkspaceSandboxStore store;
    private final WorkspaceSandboxLifecycle lifecycle;
    private final SandboxClient sandboxClient;
    private final StringRedisTemplate redis;

    private static final String LOCK_PREFIX = "sandbox:ws:lock:";

    public record CheckoutInfo(String checkoutId, String branch, String path,
                               List<String> conversationIds) {}

    /** 容器内 git 命令：加 safe.directory 豁免 dubious ownership（宿主 root 克隆的库） */
    private String git(String workDir, String... args) {
        return "git -c safe.directory='*' -C " + workDir + " " + String.join(" ", args);
    }

    /**
     * 按分支名幂等获取 checkoutId：已有该分支的 worktree 直接复用（分支与目录一一对应），
     * 否则懒创建。远程分支（origin/xxx）→ 本地名 xxx 并建立跟踪分支。
     */
    public String ensureCheckout(String workspaceId, String userId, String tenantId, String branch) {
        String local = branch.startsWith("origin/") ? branch.substring("origin/".length()) : branch;
        if (local.isEmpty() || "HEAD".equals(local)) {
            throw new IllegalStateException("非法分支: " + branch);
        }
        for (CheckoutInfo c : listCheckouts(workspaceId, userId, tenantId)) {
            if (local.equals(c.branch())) {
                return c.checkoutId();
            }
        }
        return createWorktree(workspaceId, userId, tenantId, local,
                branch.startsWith("origin/") ? branch : null);
    }

    /** 新建 worktree checkout（懒创建）；返回新 checkoutId */
    public String createWorktree(String workspaceId, String userId, String tenantId,
                                  String branch, String fromRef) {
        String sessionId = lifecycle.ensureWorkspaceSession(workspaceId, userId, tenantId);
        String lockKey = LOCK_PREFIX + workspaceId;
        if (Boolean.FALSE.equals(redis.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(60)))) {
            throw new IllegalStateException("工作区 git 操作繁忙，请稍后重试");
        }
        try {
            // worktree 目录名 = 稳定 ID（wt-{uuid}），分支与目录一一对应（同分支复用同目录）
            String checkoutId = "wt-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            String path = "/workspace/" + checkoutId;
            String ref = StringUtils.hasText(fromRef) ? fromRef.strip() : "HEAD";
            // 裸库本地分支已存在（含 clone --mirror 镜像的全部分支）→ 直接检出；否则从 ref 新建
            String cmd = branchExists(sessionId, branch)
                    ? git(REPO_PATH, "worktree", "add", path, branch)
                    : git(REPO_PATH, "worktree", "add", path, "-b", branch, ref);
            ToolInvokeResponse resp = sandboxClient.invoke(sessionId, "exec",
                    Map.of("command", cmd, "cwd", EXEC_CWD));
            if (!resp.ok()) {
                throw new IllegalStateException("worktree create failed: " + resp.output());
            }
            return checkoutId;
        } finally {
            redis.delete(lockKey);
        }
    }

    /** 裸库本地分支是否已存在 */
    private boolean branchExists(String sessionId, String branch) {
        ToolInvokeResponse resp = sandboxClient.invoke(sessionId, "exec",
                Map.of("command", git(REPO_PATH, "show-ref", "--verify", "--quiet",
                        "refs/heads/" + branch), "cwd", EXEC_CWD));
        return resp.ok();
    }

    /** 列出工作区所有 worktree checkout（repo.git 自身除外），分支实时读 git */
    public List<CheckoutInfo> listCheckouts(String workspaceId, String userId, String tenantId) {
        String sessionId = lifecycle.ensureWorkspaceSession(workspaceId, userId, tenantId);
        ToolInvokeResponse resp = sandboxClient.invoke(sessionId, "exec",
                Map.of("command", git(REPO_PATH, "worktree", "list", "--porcelain"), "cwd", EXEC_CWD));
        List<CheckoutInfo> result = new ArrayList<>();
        if (resp.output() == null || resp.output().isBlank()) {
            return result;
        }
        // porcelain 块：worktree {path}\nHEAD {sha}\n[branch refs/heads/{name}]\n\n（裸库自身为 worktree {repoPath}\nbare）
        String[] blocks = resp.output().split("\n\\s*\n");
        for (String block : blocks) {
            String path = null;
            for (String line : block.lines().toList()) {
                String t = line.trim();
                if (t.startsWith("worktree ")) {
                    path = t.substring("worktree ".length()).strip();
                }
            }
            // 跳过裸库自身（bare 块无 worktree 头）
            if (path == null || !path.startsWith("/workspace/") || path.equals(REPO_PATH)) {
                continue;
            }
            String checkoutId = path.substring("/workspace/".length()).strip();
            result.add(new CheckoutInfo(checkoutId, currentBranch(sessionId, path), path, List.of()));
        }
        return result;
    }

    /** 删除 worktree checkout（用户显式触发） */
    public void removeWorktree(String workspaceId, String userId, String tenantId, String checkoutId) {
        String sessionId = lifecycle.ensureWorkspaceSession(workspaceId, userId, tenantId);
        String lockKey = LOCK_PREFIX + workspaceId;
        if (Boolean.FALSE.equals(redis.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(30)))) {
            throw new IllegalStateException("工作区 git 操作繁忙，请稍后重试");
        }
        try {
            String path = "/workspace/" + checkoutId;
            String branch = currentBranch(sessionId, path);
            String cmd = git(REPO_PATH, "worktree", "remove", path, "--force");
            if (StringUtils.hasText(branch)) {
                cmd += " && " + git(REPO_PATH, "branch", "-D", branch);
            }
            sandboxClient.invoke(sessionId, "exec", Map.of("command", cmd, "cwd", EXEC_CWD));
        } finally {
            redis.delete(lockKey);
        }
    }

    /** 实时读取 worktree 当前分支；detached HEAD 返回空串 */
    private String currentBranch(String sessionId, String path) {
        try {
            ToolInvokeResponse resp = sandboxClient.invoke(sessionId, "exec",
                    Map.of("command", git(path, "branch", "--show-current"), "cwd", path));
            if (!resp.ok() || resp.output() == null) {
                return "";
            }
            return resp.output().strip();
        } catch (Exception e) {
            log.warn("[WorkspaceCheckout] currentBranch failed path={}: {}", path, e.getMessage());
            return "";
        }
    }
}
