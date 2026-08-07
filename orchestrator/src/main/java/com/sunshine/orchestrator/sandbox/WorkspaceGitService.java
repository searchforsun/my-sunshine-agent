package com.sunshine.orchestrator.sandbox;

import com.sunshine.common.sandbox.ToolInvokeResponse;
import com.sunshine.orchestrator.client.SandboxClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** git status --porcelain 单行：code + path（重命名/复制取新路径） */
    private record StatusEntry(String code, String path) {
    }

    /** 解析 porcelain 行（XY path / XY old -> new） */
    private static List<StatusEntry> parsePorcelainStatus(String out) {
        List<StatusEntry> list = new ArrayList<>();
        for (String line : out.lines().toList()) {
            if (line == null || line.length() < 4) {
                continue;
            }
            String code = line.substring(0, 2);
            String rest = line.substring(3).strip();
            if (rest.isEmpty()) {
                continue;
            }
            int arrow = rest.lastIndexOf(" -> ");
            if (arrow >= 0) {
                rest = rest.substring(arrow + 4).strip();
            }
            list.add(new StatusEntry(code, rest));
        }
        return list;
    }

    /** porcelain 状态码归一：MM/ M/A → 单一变更字母；?? → ? */
    private static String normalizeStatus(String code) {
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '?') {
                return "?";
            }
            if (c != ' ') {
                return String.valueOf(c);
            }
        }
        return "?";
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 无意义的目录/文件段：整段命中即忽略（node_modules/target 等依赖与构建产物） */
    private static final Set<String> IGNORED_PATH_SEGMENTS = Set.of(
            "node_modules", "target", "dist", "build", "out", ".next", ".nuxt",
            ".venv", "venv", "__pycache__", ".idea", ".vscode", ".git", "logs",
            "coverage", ".cache", ".DS_Store", "Thumbs.db", ".gradle", ".mvn",
            ".terraform", "vendor", ".pytest_cache");

    private static boolean isIgnoredDiffPath(String path) {
        if (path == null || path.isBlank()) {
            return true;
        }
        // 未跟踪目录整体（git status 输出 dir/）：无 diff 基线，作为改动条目无意义
        if (path.endsWith("/")) {
            return true;
        }
        for (String seg : path.split("/")) {
            if (seg != null && IGNORED_PATH_SEGMENTS.contains(seg)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 工作区 diff 摘要：合并（HEAD vs 工作区）+ 已暂存（HEAD vs 暂存区）+ 未暂存（暂存区 vs 工作区）三套计数。
     * 未跟踪文件无 staged，unstaged 即新增行数。过滤依赖/构建产物等无意义目录的改动。
     */
    public List<Map<String, Object>> gitDiffSummary(String workspaceId, String checkoutId, String userId, String tenantId) {
        String sessionId = lifecycle.ensureWorkspaceSession(workspaceId, userId, tenantId);
        String dir = worktreePath(checkoutId);
        List<StatusEntry> entries = parsePorcelainStatus(
                execInSandbox(sessionId, git(dir, "status", "--porcelain")));
        if (entries.isEmpty()) {
            return List.of();
        }
        Map<String, long[]> merged = new LinkedHashMap<>();
        Map<String, long[]> staged = new LinkedHashMap<>();
        Map<String, long[]> unstaged = new LinkedHashMap<>();
        Map<String, Boolean> binary = new HashMap<>();
        try {
            // numstat vs HEAD：合并（已暂存 + 未暂存）；二进制行 add/del 为 "-"
            parseNumstat(execInSandbox(sessionId, git(dir, "diff", "HEAD", "--numstat")), merged, binary);
        } catch (RuntimeException e) {
            log.warn("[WorkspaceGit] gitDiffSummary merged numstat failed ws={}: {}", workspaceId, e.getMessage());
        }
        try {
            // numstat vs 暂存区：仅已暂存改动
            parseNumstat(execInSandbox(sessionId, git(dir, "diff", "--cached", "--numstat")), staged, binary);
        } catch (RuntimeException e) {
            log.warn("[WorkspaceGit] gitDiffSummary staged numstat failed ws={}: {}", workspaceId, e.getMessage());
        }
        try {
            // numstat 暂存区 vs 工作区：仅未暂存改动（不含未跟踪文件）
            parseNumstat(execInSandbox(sessionId, git(dir, "diff", "--numstat")), unstaged, binary);
        } catch (RuntimeException e) {
            log.warn("[WorkspaceGit] gitDiffSummary unstaged numstat failed ws={}: {}", workspaceId, e.getMessage());
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (StatusEntry entry : entries) {
            if (isIgnoredDiffPath(entry.path())) {
                continue;
            }
            long add = 0;
            long del = 0;
            long stagedAdd = 0;
            long stagedDel = 0;
            long unstagedAdd = 0;
            long unstagedDel = 0;
            boolean bin = false;
            if ("??".equals(entry.code())) {
                // 未跟踪文件：无 diff 基线，合并/未暂存行数即新增行数
                add = countWorktreeLines(sessionId, dir, entry.path());
                unstagedAdd = add;
            } else {
                long[] m = merged.get(entry.path());
                if (m != null) {
                    add = m[0];
                    del = m[1];
                }
                long[] s = staged.get(entry.path());
                if (s != null) {
                    stagedAdd = s[0];
                    stagedDel = s[1];
                }
                long[] u = unstaged.get(entry.path());
                if (u != null) {
                    unstagedAdd = u[0];
                    unstagedDel = u[1];
                }
                bin = binary.getOrDefault(entry.path(), false);
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", entry.path());
            item.put("status", normalizeStatus(entry.code()));
            item.put("rawStatus", entry.code());
            item.put("added", add);
            item.put("deleted", del);
            item.put("binary", bin);
            Map<String, Object> stagedCounts = new LinkedHashMap<>();
            stagedCounts.put("added", stagedAdd);
            stagedCounts.put("deleted", stagedDel);
            item.put("staged", stagedCounts);
            Map<String, Object> unstagedCounts = new LinkedHashMap<>();
            unstagedCounts.put("added", unstagedAdd);
            unstagedCounts.put("deleted", unstagedDel);
            item.put("unstaged", unstagedCounts);
            result.add(item);
        }
        return result;
    }

    /** 解析 numstat 文本 -> path -> [add, del]；二进制行写入 binary map */
    private static void parseNumstat(String out, Map<String, long[]> counts, Map<String, Boolean> binary) {
        for (String line : out.lines().toList()) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\t", -1);
            if (parts.length < 3) {
                continue;
            }
            boolean bin = "-".equals(parts[0]) || "-".equals(parts[1]);
            long add = bin ? 0 : parseLong(parts[0]);
            long del = bin ? 0 : parseLong(parts[1]);
            counts.put(parts[2], new long[]{add, del});
            if (bin) {
                binary.put(parts[2], true);
            }
        }
    }

    /** 未跟踪文件行数（wc -l；失败返回 0） */
    private long countWorktreeLines(String sessionId, String dir, String path) {
        try {
            String out = execInSandbox(sessionId, "wc -l -- " + shq(dir + "/" + path)).trim();
            return parseLong(out.split("\\s+")[0]);
        } catch (RuntimeException e) {
            log.warn("[WorkspaceGit] countWorktreeLines failed path={}: {}", path, e.getMessage());
            return 0;
        }
    }

    /**
     * 单文件 diff 详情：合并（HEAD vs 工作区）+ 已暂存（HEAD vs 暂存区）+ 未暂存（暂存区 vs 工作区）。
     * 未跟踪文件与 /dev/null 比对（无 staged），均解析为结构化 diff 行。
     */
    public Map<String, Object> gitDiffDetail(String workspaceId, String checkoutId, String userId, String tenantId, String path) {
        String sessionId = lifecycle.ensureWorkspaceSession(workspaceId, userId, tenantId);
        String dir = worktreePath(checkoutId);
        boolean untracked = isUntracked(sessionId, dir, path);
        String merged = "";
        String staged = "";
        String unstaged = "";
        try {
            if (untracked) {
                // 未跟踪：与 /dev/null 比较；--no-index 有差异时退出码为 1，追加 || true 掩掉避免 exec 报错
                merged = execInSandbox(sessionId,
                        git(dir, "diff", "--no-index", "--unified=3", "/dev/null", path) + " || true");
            } else {
                merged = execInSandbox(sessionId, git(dir, "diff", "--unified=3", "HEAD", "--", path));
            }
        } catch (RuntimeException e) {
            log.warn("[WorkspaceGit] gitDiffDetail merged diff failed ws={}: {}", workspaceId, e.getMessage());
        }
        try {
            staged = execInSandbox(sessionId, git(dir, "diff", "--unified=3", "--cached", "--", path));
        } catch (RuntimeException e) {
            log.warn("[WorkspaceGit] gitDiffDetail staged diff failed ws={}: {}", workspaceId, e.getMessage());
        }
        try {
            if (untracked) {
                unstaged = execInSandbox(sessionId,
                        git(dir, "diff", "--no-index", "--unified=3", "/dev/null", path) + " || true");
            } else {
                unstaged = execInSandbox(sessionId, git(dir, "diff", "--unified=3", "--", path));
            }
        } catch (RuntimeException e) {
            log.warn("[WorkspaceGit] gitDiffDetail unstaged diff failed ws={}: {}", workspaceId, e.getMessage());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", path);
        result.put("lines", UnifiedDiffLines.parse(merged));
        result.put("staged", Map.of(
                "present", !staged.isBlank(),
                "lines", UnifiedDiffLines.parse(staged)));
        result.put("unstaged", Map.of(
                "present", !unstaged.isBlank(),
                "lines", UnifiedDiffLines.parse(unstaged)));
        return result;
    }

    /** 单文件是否未跟踪（git status --porcelain -- path 以 ?? 开头） */
    private boolean isUntracked(String sessionId, String dir, String path) {
        try {
            String out = execInSandbox(sessionId, git(dir, "status", "--porcelain", "--", path));
            return out != null && out.startsWith("??");
        } catch (RuntimeException e) {
            log.warn("[WorkspaceGit] isUntracked failed path={}: {}", path, e.getMessage());
            return false;
        }
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

    /** 撤回暂存（仅清暂存区，保留工作区改动）：git restore --staged */
    public void gitUnstage(String workspaceId, String checkoutId, String userId, String tenantId,
                           List<String> files) {
        if (files == null || files.isEmpty()) {
            throw new RuntimeException("撤回文件不能为空");
        }
        String sessionId = lifecycle.ensureWorkspaceSession(workspaceId, userId, tenantId);
        String dir = worktreePath(checkoutId);
        List<String> cmd = new ArrayList<>(List.of("restore", "--staged", "--"));
        cmd.addAll(files);
        execInSandbox(sessionId, git(dir, cmd.toArray(new String[0])));
    }

    /**
     * 回退文件改动到 HEAD（含清暂存区）；未跟踪文件直接删除。
     * 已跟踪文件走 git restore --staged --worktree；未跟踪文件 rm。
     */
    public void gitRevert(String workspaceId, String checkoutId, String userId, String tenantId,
                          List<String> files) {
        if (files == null || files.isEmpty()) {
            throw new RuntimeException("回退文件不能为空");
        }
        String sessionId = lifecycle.ensureWorkspaceSession(workspaceId, userId, tenantId);
        String dir = worktreePath(checkoutId);
        Set<String> untracked = new HashSet<>();
        for (StatusEntry e : parsePorcelainStatus(execInSandbox(sessionId, git(dir, "status", "--porcelain")))) {
            if ("??".equals(e.code())) {
                untracked.add(e.path());
            }
        }
        List<String> tracked = new ArrayList<>();
        List<String> toDelete = new ArrayList<>();
        for (String p : files) {
            if (untracked.contains(p)) {
                toDelete.add(p);
            } else {
                tracked.add(p);
            }
        }
        if (!tracked.isEmpty()) {
            List<String> cmd = new ArrayList<>(List.of("restore", "--staged", "--worktree", "--"));
            cmd.addAll(tracked);
            execInSandbox(sessionId, git(dir, cmd.toArray(new String[0])));
        }
        for (String p : toDelete) {
            execInSandbox(sessionId, "rm -f " + shq(dir + "/" + p));
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
