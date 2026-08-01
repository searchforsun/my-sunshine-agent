package com.sunshine.orchestrator.sandbox;

import com.sunshine.orchestrator.workspace.entity.AgentWorkspaceEntity;
import com.sunshine.orchestrator.workspace.repo.AgentWorkspaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class WorkspaceGitService {

    private final AgentWorkspaceRepository workspaceRepo;
    private final WebClient.Builder webClientBuilder;

    @Value("${sandbox.host-workspace-root:/var/lib/sunshine-sandbox}")
    private String hostWorkspaceRoot;

    @Value("${auth-service.base-url:http://localhost:8210}")
    private String authBaseUrl;

    /** 共享裸镜像库目录（repos/{wsId}.git，与 worktrees 分离）——所有分支对象都在这，会话工作目录为其 worktree */
    private Path repoDir(String workspaceId) {
        AgentWorkspaceEntity ws = workspaceRepo.findById(workspaceId)
                .orElseThrow(() -> new RuntimeException("工作区不存在: " + workspaceId));
        return Path.of(hostWorkspaceRoot, "repos", workspaceId + ".git");
    }

    /** 会话工作目录（= 容器内 /workspace/{checkoutId}）；分支与目录一一对应 */
    private Path checkoutDir(String workspaceId, String checkoutId) {
        return Path.of(hostWorkspaceRoot, "workspaces", workspaceId, checkoutId);
    }

    private Map<String, String> fetchCredentials(String userId, String repoUrl) {
        try {
            String host = new java.net.URI(repoUrl).getHost();
            WebClient client = webClientBuilder.baseUrl(authBaseUrl).build();
            @SuppressWarnings("unchecked")
            var resp = (Map<String, Object>) client.get()
                    .uri(uriBuilder -> uriBuilder.path("/api/auth/git-credentials")
                            .queryParam("host", host).build())
                    .header("x-user-id", userId)
                    .retrieve().bodyToMono(Map.class)
                    .block(java.time.Duration.ofSeconds(15));
            if (resp != null && resp.get("data") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, String> data = (Map<String, String>) resp.get("data");
                return data != null ? data : Map.of();
            }
            return Map.of();
        } catch (Exception e) {
            log.warn("[WorkspaceGit] creds failed: {}", e.getMessage());
            return Map.of();
        }
    }

    /** 列出所有分支（本地 + 远程）；基于共享裸库，本地分支即远程全部分支 */
    public List<Map<String, Object>> listBranches(String workspaceId) {
        Path dir = repoDir(workspaceId);
        ensureRepo(dir);
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            // 本地分支（裸库 refs/heads = 远程全部分支）
            String local = runIn(dir, "branch", "--format=%(refname:short) %(HEAD)");
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
            // 远程分支（镜像库可能残留 origin/HEAD 等）
            String remote = runIn(dir, "branch", "-r", "--format=%(refname:short)");
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

    /** 从已有分支创建新分支（基于裸库，任意分支引用均可用） */
    public void createBranch(String workspaceId, String branchName, String fromBranch) {
        Path dir = repoDir(workspaceId);
        ensureRepo(dir);
        if (branchName == null || branchName.isBlank()) throw new RuntimeException("分支名不能为空");
        runIn(dir, "branch", branchName, fromBranch != null && !fromBranch.isBlank() ? fromBranch : "HEAD");
    }

    /** git status --porcelain（作用于会话工作目录） */
    public Map<String, Object> gitStatus(String workspaceId, String checkoutId) {
        Path dir = checkoutDir(workspaceId, checkoutId);
        ensureCheckoutDir(dir, checkoutId);
        String out = runIn(dir, "status", "--porcelain");
        String branch = runIn(dir, "branch", "--show-current").trim();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("branch", branch);
        result.put("files", out);
        return result;
    }

    /** git add（作用于会话工作目录） */
    public void gitStage(String workspaceId, String checkoutId, List<String> files, boolean all) {
        Path dir = checkoutDir(workspaceId, checkoutId);
        ensureCheckoutDir(dir, checkoutId);
        if (all) {
            runIn(dir, "add", "-A");
            return;
        }
        if (files != null && !files.isEmpty()) {
            List<String> cmd = new ArrayList<>(List.of("add"));
            cmd.addAll(files);
            runIn(dir, cmd.toArray(new String[0]));
        }
    }

    /** git commit（作用于会话工作目录） */
    public void gitCommit(String workspaceId, String checkoutId, String message) {
        Path dir = checkoutDir(workspaceId, checkoutId);
        ensureCheckoutDir(dir, checkoutId);
        if (message == null || message.isBlank()) throw new RuntimeException("commit 信息不能为空");
        runIn(dir, "commit", "-m", message);
    }

    /** git push (含 GIT_ASKPASS 凭据注入；作用于会话工作目录) */
    public void gitPush(String workspaceId, String checkoutId, String userId) {
        Path dir = checkoutDir(workspaceId, checkoutId);
        ensureCheckoutDir(dir, checkoutId);
        AgentWorkspaceEntity ws = workspaceRepo.findById(workspaceId)
                .orElseThrow(() -> new RuntimeException("工作区不存在: " + workspaceId));
        Map<String, String> cred = fetchCredentials(userId, ws.getRepoUrl());
        String token = cred.getOrDefault("token", "");
        File askpass = null;
        try {
            if (!token.isEmpty()) {
                askpass = writeAskpass(dir, token);
                ProcessBuilder pb = new ProcessBuilder("git", "-c", "safe.directory='*'", "push", "-u", "origin", "HEAD");
                pb.directory(dir.toFile());
                pb.redirectErrorStream(true);
                pb.environment().put("GIT_ASKPASS", askpass.getAbsolutePath());
                pb.environment().put("GIT_TERMINAL_PROMPT", "0");
                Process p = pb.start();
                boolean done = p.waitFor(2, TimeUnit.MINUTES);
                if (!done) { p.destroyForcibly(); throw new RuntimeException("git push timeout"); }
                String output = new String(p.getInputStream().readAllBytes());
                if (p.exitValue() != 0) throw new RuntimeException("git push exit " + p.exitValue() + ": " + output);
            } else {
                ProcessBuilder pb = new ProcessBuilder("git", "-c", "safe.directory='*'", "push", "-u", "origin", "HEAD");
                pb.directory(dir.toFile());
                pb.redirectErrorStream(true);
                Process p = pb.start();
                boolean done = p.waitFor(2, TimeUnit.MINUTES);
                if (!done) { p.destroyForcibly(); throw new RuntimeException("git push timeout"); }
                String output = new String(p.getInputStream().readAllBytes());
                if (p.exitValue() != 0) throw new RuntimeException("git push exit " + p.exitValue() + ": " + output);
            }
        } catch (Exception e) {
            throw new RuntimeException("git push failed: " + e.getMessage(), e);
        } finally {
            if (askpass != null) askpass.delete();
        }
    }

    /** git pull：先 fetch 裸库更新全部分支，再快进当前会话工作目录（含凭据注入） */
    public Map<String, Object> gitPull(String workspaceId, String checkoutId, String userId) {
        Path repo = repoDir(workspaceId);
        ensureRepo(repo);
        Path dir = checkoutDir(workspaceId, checkoutId);
        ensureCheckoutDir(dir, checkoutId);
        AgentWorkspaceEntity ws = workspaceRepo.findById(workspaceId)
                .orElseThrow(() -> new RuntimeException("工作区不存在: " + workspaceId));
        Map<String, String> cred = fetchCredentials(userId, ws.getRepoUrl());
        String token = cred.getOrDefault("token", "");
        String currentBranch = runIn(dir, "branch", "--show-current").trim();
        String ref = currentBranch.isEmpty() ? "HEAD" : currentBranch;
        File askpass = null;
        try {
            ProcessBuilder pb;
            if (!token.isEmpty()) {
                askpass = writeAskpass(dir, token);
                pb = new ProcessBuilder("git", "-c", "safe.directory='*'", "pull", "origin", ref);
                pb.environment().put("GIT_ASKPASS", askpass.getAbsolutePath());
                pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            } else {
                pb = new ProcessBuilder("git", "-c", "safe.directory='*'", "pull", "origin", ref);
            }
            pb.directory(dir.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean done = p.waitFor(3, TimeUnit.MINUTES);
            if (!done) { p.destroyForcibly(); throw new RuntimeException("git pull timeout"); }
            String output = new String(p.getInputStream().readAllBytes());
            if (p.exitValue() != 0) throw new RuntimeException("git pull exit " + p.exitValue() + ": " + output);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("branch", ref);
            result.put("output", output);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("git pull failed: " + e.getMessage(), e);
        } finally {
            if (askpass != null) askpass.delete();
        }
    }

    /** 刷新共享裸库：fetch --all --prune（含凭据注入）；用于同步工作区代码 */
    public Map<String, Object> gitFetchAll(String workspaceId, String userId, Path repo) {
        ensureRepo(repo);
        AgentWorkspaceEntity ws = workspaceRepo.findById(workspaceId)
                .orElseThrow(() -> new RuntimeException("工作区不存在: " + workspaceId));
        Map<String, String> cred = fetchCredentials(userId, ws.getRepoUrl());
        String token = cred.getOrDefault("token", "");
        File askpass = null;
        try {
            ProcessBuilder pb;
            if (!token.isEmpty()) {
                askpass = writeAskpass(repo, token);
                pb = new ProcessBuilder("git", "-c", "safe.directory='*'", "fetch", "--all", "--prune");
                pb.environment().put("GIT_ASKPASS", askpass.getAbsolutePath());
                pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            } else {
                pb = new ProcessBuilder("git", "-c", "safe.directory='*'", "fetch", "--all", "--prune");
            }
            pb.directory(repo.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean done = p.waitFor(3, TimeUnit.MINUTES);
            if (!done) { p.destroyForcibly(); throw new RuntimeException("git fetch timeout"); }
            String output = new String(p.getInputStream().readAllBytes());
            if (p.exitValue() != 0) throw new RuntimeException("git fetch exit " + p.exitValue() + ": " + output);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("output", output);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("git fetch failed: " + e.getMessage(), e);
        } finally {
            if (askpass != null) askpass.delete();
        }
    }

    /** 裸库就绪检查：objects 目录存在 */
    private void ensureRepo(Path dir) {
        File f = dir.toFile();
        if (!f.exists() || !new File(f, "objects").exists()) {
            throw new RuntimeException("工作区代码未就绪，请等待后台克隆完成");
        }
    }

    /** 会话工作目录就绪检查：.git 存在（worktree 的 .git 是指向裸库的指针文件） */
    private void ensureCheckoutDir(Path dir, String checkoutId) {
        File f = dir.toFile();
        if (!f.exists() || !new File(f, ".git").exists()) {
            throw new RuntimeException("会话工作目录未就绪，请重新发送消息触发拉取: " + checkoutId);
        }
    }

    private String runIn(Path dir, String... cmd) {
        try {
            String[] full = new String[cmd.length + 3];
            full[0] = "git";
            full[1] = "-c";
            full[2] = "safe.directory='*'";
            System.arraycopy(cmd, 0, full, 3, cmd.length);
            ProcessBuilder pb = new ProcessBuilder(full);
            pb.directory(dir.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean done = p.waitFor(30, TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); throw new RuntimeException("command timeout: " + cmd[0]); }
            String out = new String(p.getInputStream().readAllBytes());
            if (p.exitValue() != 0) throw new RuntimeException("git " + cmd[0] + " exit " + p.exitValue() + ": " + out);
            return out;
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private static File writeAskpass(Path parentDir, String token) throws Exception {
        File script = File.createTempFile("git-askpass-", ".sh", parentDir.toFile());
        script.setExecutable(true, true);
        script.setReadable(false, false);
        script.setReadable(true, true);
        String content = "#!/bin/sh\necho '" + token.replace("'", "'\\''") + "'\n";
        Files.writeString(script.toPath(), content);
        return script;
    }
}
