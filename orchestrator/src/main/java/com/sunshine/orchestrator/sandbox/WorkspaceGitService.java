package com.sunshine.orchestrator.sandbox;

import com.sunshine.common.sandbox.ToolInvokeResponse;
import com.sunshine.orchestrator.client.SandboxClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作区 Git 操作：stage / commit / push / pull / status / branches。
 * 全部经 SandboxClient 在容器内执行（worktree 的 .git 指向容器内路径，宿主机无法直接操作）。
 * 凭据由 clone 时写入裸库 .git-credentials + credential.helper store，容器内自动读取。
 */
@Slf4j
@Component
public class WorkspaceGitService {

    /** 容器内共享裸镜像库路径 */
    private static final String REPO_PATH = "/opt/git";
    /** exec 工具 cwd 必须是 jail 内路径 */
    private static final String EXEC_CWD = "/workspace";

    private final SandboxClient sandboxClient;
    private final WorkspaceSandboxLifecycle lifecycle;

    public WorkspaceGitService(SandboxClient sandboxClient,
                               @Lazy WorkspaceSandboxLifecycle lifecycle) {
        this.sandboxClient = sandboxClient;
        this.lifecycle = lifecycle;
    }

    /** 容器内 worktree 路径（= /workspace/{checkoutId}） */
    private static String worktreePath(String checkoutId) {
        return "/workspace/" + checkoutId;
    }

    /** 拼装容器内 git 命令（-C 指定工作目录）；参数统一 POSIX 单引号转义，避免 %() / 空格等被 sh 解析 */
    private static String git(String workDir, String... args) {
        StringBuilder cmd = new StringBuilder("git -c safe.directory='*' -C ").append(workDir);
        for (String arg : args) {
            cmd.append(' ').append(shq(arg));
        }
        return cmd.toString();
    }

    /** POSIX sh 单引号转义：包裹参数并转义内部单引号 */
    private static String shq(String arg) {
        return "'" + arg.replace("'", "'\\''") + "'";
    }

    /** 在容器内执行命令，失败抛 RuntimeException */
    private String execInSandbox(String sessionId, String command) {
        ToolInvokeResponse resp = sandboxClient.invoke(sessionId, "exec",
                Map.of("command", command, "cwd", EXEC_CWD));
        if (!resp.ok()) {
            throw new RuntimeException("git 命令执行失败: " + resp.output());
        }
        return resp.output() != null ? resp.output() : "";
    }

    /** 列出所有分支（本地 + 远程）；基于共享裸库 */
    public List<Map<String, Object>> listBranches(String workspaceId, String userId, String tenantId) {
        String sessionId = lifecycle.ensureWorkspaceSession(workspaceId, userId, tenantId);
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            String local = execInSandbox(sessionId, git(REPO_PATH, "branch", "--format=%(refname:short) %(HEAD)"));
            for (String line : local.lines().toList()) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                boolean isCurrent = trimmed.endsWith(" *");
                String name = isCurrent ? trimmed.substring(0, trimmed.length() - 2).strip() : trimmed;
                var entry = new LinkedHashMap<String, Object>();
                entry.put("name", name);
                entry.put("type", "local");
                entry.put("current", isCurrent);
                result.add(entry);
            }
            String remote = execInSandbox(sessionId, git(REPO_PATH, "branch", "-r", "--format=%(refname:short)"));
            for (String line : remote.lines().toList()) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || "origin/HEAD".equals(trimmed)) continue;
                var entry = new LinkedHashMap<String, Object>();
                entry.put("name", trimmed);
                entry.put("type", "remote");
                entry.put("current", false);
                result.add(entry);
            }
        } catch (Exception e) {
            log.warn("[WorkspaceGit] listBranches failed ws={}: {}", workspaceId, e.getMessage());
        }
        return result;
    }

    /** 从已有分支创建新分支（基于裸库） */
    public void createBranch(String workspaceId, String userId, String tenantId, String branchName, String fromBranch) {
        if (branchName == null || branchName.isBlank()) throw new RuntimeException("分支名不能为空");
        String sessionId = lifecycle.ensureWorkspaceSession(workspaceId, userId, tenantId);
        String ref = fromBranch != null && !fromBranch.isBlank() ? fromBranch : "HEAD";
        execInSandbox(sessionId, git(REPO_PATH, "branch", branchName, ref));
    }

    /** git status --porcelain（作用于 worktree） */
    public Map<String, Object> gitStatus(String workspaceId, String checkoutId, String userId, String tenantId) {
        String sessionId = lifecycle.ensureWorkspaceSession(workspaceId, userId, tenantId);
        String dir = worktreePath(checkoutId);
        String out = execInSandbox(sessionId, git(dir, "status", "--porcelain"));
        String branch = execInSandbox(sessionId, git(dir, "branch", "--show-current")).trim();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("branch", branch);
        result.put("files", out);
        return result;
    }

    /** git add（作用于 worktree） */
    public void gitStage(String workspaceId, String checkoutId, String userId, String tenantId,
                         List<String> files, boolean all) {
        String sessionId = lifecycle.ensureWorkspaceSession(workspaceId, userId, tenantId);
        String dir = worktreePath(checkoutId);
        if (all) {
            execInSandbox(sessionId, git(dir, "add", "-A"));
            return;
        }
        if (files != null && !files.isEmpty()) {
            List<String> cmd = new ArrayList<>(List.of("add"));
            cmd.addAll(files);
            execInSandbox(sessionId, git(dir, cmd.toArray(new String[0])));
        }
    }

    /** git commit（作用于 worktree） */
    public void gitCommit(String workspaceId, String checkoutId, String userId, String tenantId, String message) {
        if (message == null || message.isBlank()) throw new RuntimeException("commit 信息不能为空");
        String sessionId = lifecycle.ensureWorkspaceSession(workspaceId, userId, tenantId);
        String dir = worktreePath(checkoutId);
        // 容器内 git 缺省无 user identity，提交前写入 local 配置（绑定当前操作者）
        execInSandbox(sessionId, git(dir, "config", "user.email", userId + "@sunshine.local"));
        execInSandbox(sessionId, git(dir, "config", "user.name", userId));
        // message 由 git() 统一做 POSIX 单引号转义
        execInSandbox(sessionId, git(dir, "commit", "-m", message));
    }

    /** git push（凭据由裸库 credential.helper store 自动注入） */
    public void gitPush(String workspaceId, String checkoutId, String userId, String tenantId) {
        String sessionId = lifecycle.ensureWorkspaceSession(workspaceId, userId, tenantId);
        String dir = worktreePath(checkoutId);
        execInSandbox(sessionId, git(dir, "push", "-u", "origin", "HEAD"));
    }

    /** git pull：从远程拉取并快进当前分支（凭据由裸库 credential.helper store 自动注入） */
    public Map<String, Object> gitPull(String workspaceId, String checkoutId, String userId, String tenantId) {
        String sessionId = lifecycle.ensureWorkspaceSession(workspaceId, userId, tenantId);
        String dir = worktreePath(checkoutId);
        String currentBranch = execInSandbox(sessionId, git(dir, "branch", "--show-current")).trim();
        String ref = currentBranch.isEmpty() ? "HEAD" : currentBranch;
        String output = execInSandbox(sessionId, git(dir, "pull", "origin", ref));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("branch", ref);
        result.put("output", output);
        return result;
    }

    /** 刷新共享裸库：fetch --all --prune（凭据由 credential.helper store 注入） */
    public Map<String, Object> gitFetchAll(String workspaceId, String userId, String tenantId) {
        String sessionId = lifecycle.ensureWorkspaceSession(workspaceId, userId, tenantId);
        String output = execInSandbox(sessionId, git(REPO_PATH, "fetch", "--all", "--prune"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("output", output);
        return result;
    }
}
