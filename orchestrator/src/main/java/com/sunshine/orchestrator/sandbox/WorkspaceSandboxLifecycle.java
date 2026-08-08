package com.sunshine.orchestrator.sandbox;

import com.sunshine.common.sandbox.CreateSessionRequest;
import com.sunshine.common.sandbox.SandboxPolicy;
import com.sunshine.orchestrator.client.SandboxClient;
import com.sunshine.orchestrator.workspace.entity.AgentWorkspaceEntity;
import com.sunshine.orchestrator.workspace.repo.AgentWorkspaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class WorkspaceSandboxLifecycle {

    private final WorkspaceSandboxStore store;
    private final AgentWorkspaceRepository workspaceRepo;
    private final SandboxClient sandboxClient;
    private final WorkspaceGitService gitService;
    private final WebClient.Builder webClientBuilder;

    @Value("${sandbox.host-workspace-root:/var/lib/sunshine-sandbox}")
    private String hostWorkspaceRoot;

    public String ensureWorkspaceSession(String workspaceId, String userId, String tenantId) {
        WorkspaceSandboxBinding binding = store.find(tenantId, workspaceId).orElse(null);
        // 已有 binding 但 clone 失败残留 → 先重试 clone --mirror，避免分支下拉/会话一直拿不到代码
        if (binding != null && binding.cloneState() != null && binding.cloneState().startsWith("failed")) {
            try {
                AgentWorkspaceEntity retryWs = workspaceRepo.findById(workspaceId)
                        .orElseThrow(() -> new IllegalStateException("工作区不存在: " + workspaceId));
                String retryDir = hostWorkspaceRoot + "/workspaces/" + workspaceId;
                Path retryRepo = repoDir(workspaceId);
                Files.createDirectories(Path.of(retryDir));
                cloneMirrorRepo(retryWs.getRepoUrl(), userId, retryRepo);
                runFixPerms(Path.of(retryDir));
                runFixPerms(retryRepo);
                binding = binding.withCloneState("done");
                store.save(binding);
                log.info("[WorkspaceLifecycle] retry clone ok ws={}", workspaceId);
            } catch (Exception e) {
                log.warn("[WorkspaceLifecycle] retry clone failed ws={}: {}", workspaceId, e.getMessage());
            }
        }
        if (binding != null) {
            if (sandboxClient.sessionRunning(binding.sessionId())) {
                store.touch(tenantId, workspaceId);
                return binding.sessionId();
            }
            if (sandboxClient.sessionAlive(binding.sessionId())) {
                sandboxClient.startSession(binding.sessionId());
                store.touch(tenantId, workspaceId);
                return binding.sessionId();
            }
            store.remove(tenantId, workspaceId);
        }
        AgentWorkspaceEntity ws = workspaceRepo.findById(workspaceId)
                .orElseThrow(() -> new IllegalStateException("工作区不存在: " + workspaceId));
        String hostDir = hostWorkspaceRoot + "/workspaces/" + workspaceId;
        // 共享 git 裸镜像库在 repos/{wsId}.git（与 worktrees 分离，避免挂载进 /workspace 暴露给 AI）
        Path repoPath = repoDir(workspaceId);
        String cloneState = "done";
        try {
            // 先建目录：clone --mirror 的 askpass 临时脚本要写到该目录下
            Files.createDirectories(Path.of(hostDir));
            Files.createDirectories(repoPath.getParent());
            cloneMirrorRepo(ws.getRepoUrl(), userId, repoPath);
            // 幂等 chown：保证已有/新 clone 的代码容器内可读写
            runFixPerms(Path.of(hostDir));
            runFixPerms(repoPath);
        } catch (Exception e) {
            cloneState = "failed:" + truncate(e.getMessage(), 120);
            log.warn("[WorkspaceLifecycle] clone mirror failed ws={}: {}", workspaceId, e.getMessage());
        }
        String sessionId = sandboxClient.createSession(new CreateSessionRequest(
                userId, tenantId, null, "workspace-" + workspaceId,
                fullSessionPolicy(ws), Map.of(), Map.of(), hostDir,
                repoPath.toString()));
        binding = new WorkspaceSandboxBinding(
                sessionId, userId, tenantId, workspaceId,
                WorkspaceSandboxBinding.STATE_RUNNING, System.currentTimeMillis(),
                ws.getRepoUrl(), ws.getRepoBranch(), cloneState,
                ws.getMemoryMb(), ws.getCpus().doubleValue(), ws.getImage());
        store.save(binding);
        log.info("[WorkspaceLifecycle] session={} ws={} clone={}", sessionId, workspaceId, cloneState);
        return sessionId;
    }

    private SandboxPolicy fullSessionPolicy(AgentWorkspaceEntity ws) {
        return new SandboxPolicy(
                "docker", ws.getImage(), 120, ws.getMemoryMb(),
                ws.getCpus().doubleValue(),
                List.of(), List.of(), "task");
    }

    /**
     * 克隆共享 git 裸镜像库（--mirror：拉取全部分支 refs，无工作树）。
     * 幂等：repo.git 已存在则跳过。创建项目时调用，会话工作目录由 Worktree 懒创建。
     * clone 后立即去掉 mirror 语义（mirror=true + fetch=+refs/*:refs/* 会让容器内 git push
     * 走 mirror refspec 而失败），并把凭据写入 credential store 供容器内 git 使用。
     */
    private void cloneMirrorRepo(String repoUrl, String userId, Path target) {
        String host = extractHost(repoUrl);
        Map<String, String> cred = fetchGitCredentials(userId, host);
        String token = cred.getOrDefault("token", "");
        File dir = target.toFile();
        // bare 库存在（有 objects 目录）视为已克隆
        if (dir.exists() && new File(dir, "objects").exists()) {
            return;
        }
        deleteTreeQuietly(dir);
        File askpassScript = null;
        try {
            List<String> cmd = new ArrayList<>(List.of("git", "clone", "--mirror"));
            cmd.add(repoUrl);
            cmd.add(dir.getAbsolutePath());
            ProcessBuilder pb;
            if (!token.isEmpty()) {
                askpassScript = writeAskpassScript(target.getParent(), token);
                pb = new ProcessBuilder(cmd);
                pb.environment().put("GIT_ASKPASS", askpassScript.getAbsolutePath());
                pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            } else {
                pb = new ProcessBuilder(cmd);
            }
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean done = p.waitFor(5, TimeUnit.MINUTES);
            if (!done) {
                p.destroyForcibly();
                throw new RuntimeException("git clone --mirror timeout after 5min");
            }
            String output = new String(p.getInputStream().readAllBytes());
            int code = p.exitValue();
            if (code != 0) {
                deleteTreeQuietly(dir);
                throw new RuntimeException("git clone --mirror exit " + code + ": " + truncate(output, 200));
            }
            // 去掉 mirror 语义：容器内 worktree 的 git push 走常规 refspec（当前分支→同名远程分支）
            try {
                runQuiet("git", "-C", dir.getAbsolutePath(), "config", "--unset",
                        "remote.origin.mirror");
            } catch (Exception e) {
                log.warn("[WorkspaceLifecycle] clone unset mirror failed: {}", e.getMessage());
            }
            // 保留镜像 fetch（refs/*:refs/*），保证 sync 仍能拉取全部分支；push 走 simple 默认语义
            if (!token.isEmpty()) {
                writeGitCredentialStore(target, repoUrl, token);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            deleteTreeQuietly(dir);
            throw new RuntimeException("git clone --mirror interrupted", e);
        } catch (IOException e) {
            deleteTreeQuietly(dir);
            throw new RuntimeException("git clone --mirror io error", e);
        } finally {
            if (askpassScript != null) askpassScript.delete();
        }
    }

    /** 写容器内可读的 git 凭据：裸库内 .git-credentials + 裸库 credential.helper 指向它（容器内 /opt/git/.git-credentials） */
    private void writeGitCredentialStore(Path repoGit, String repoUrl, String token) {
        try {
            String host = extractHost(repoUrl);
            String username = extractRepoOwner(repoUrl);
            if (host.isEmpty() || token.isEmpty()) return;
            // GitHub/GitLab 均支持 https://<用户名>:<PAT>@host；owner 段（如 searchforsun/proj → searchforsun）作用户名
            Path credFile = repoGit.resolve(".git-credentials");
            if (username.isEmpty()) {
                Files.writeString(credFile, "https://oauth2:" + token + "@" + host + "\n");
            } else {
                Files.writeString(credFile, "https://" + username + ":" + token + "@" + host + "\n");
            }
            // 容器内 worktree 共享裸库 config → credential.helper 生效；path 用容器内挂载路径（/opt/git 在 jail 外，AI 工具读不到）
            runQuiet("git", "-C", repoGit.toFile().getAbsolutePath(), "config",
                    "credential.helper", "store --file=/opt/git/.git-credentials");
        } catch (Exception e) {
            log.warn("[WorkspaceLifecycle] writeGitCredentialStore failed: {}", e.getMessage());
        }
    }

    /** 从 repoUrl 提取仓库 owner 段（https://host/owner/repo.git 或 https://host:port/owner/repo → owner） */
    private static String extractRepoOwner(String repoUrl) {
        try {
            String path = new java.net.URL(repoUrl).getPath().strip().replaceAll("^/+", "");
            String[] segs = path.split("/");
            if (segs.length >= 2 && !segs[0].isBlank()) {
                return segs[0];
            }
        } catch (Exception ignore) {
            // fall through
        }
        return "";
    }

    private static void deleteTreeQuietly(File dir) {
        if (dir == null || !dir.exists()) return;
        try {
            Files.walk(dir.toPath())
                    .sorted(java.util.Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (IOException ignore) {
            // quiet cleanup
        }
    }

    /**
     * 修复 clone 产物的权限，保证两边都可读写：
     * - 属主恢复为宿主 root（orchestrator 以 root 跑 host 侧 git，避免 dubious ownership）
     * - 目录全 777（容器内 UID 10001 沙箱用户可写，AI 才能改代码 / git 才能写 index.lock）
     */
    private void runFixPerms(Path target) {
        try {
            // 属主恢复为宿主 root（host 侧 orchestrator 以 root 跑 git，避免 dubious ownership）
            runQuiet("chown", "-R", "0:0", target.toAbsolutePath().toString());
            // 全 777：容器内 UID 10001 沙箱用户可写，AI 才能改代码 / git 才能写 index.lock
            runQuiet("chmod", "-R", "a+rwx", target.toAbsolutePath().toString());
        } catch (Exception e) {
            log.warn("[WorkspaceLifecycle] fixPerms failed target={}: {}", target, e.getMessage());
        }
    }

    private void runQuiet(String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        boolean done = p.waitFor(2, TimeUnit.MINUTES);
        if (!done) {
            p.destroyForcibly();
            throw new RuntimeException(cmd[0] + " timeout");
        }
        if (p.exitValue() != 0) {
            throw new RuntimeException(cmd[0] + " exit " + p.exitValue());
        }
    }

    private static File writeAskpassScript(Path parentDir, String token) throws IOException {
        File script = File.createTempFile("git-askpass-", ".sh", parentDir.toFile());
        script.setExecutable(true, true);
        script.setReadable(false, false);
        script.setReadable(true, true);
        String content = "#!/bin/sh\necho " + shellEscape(token) + "\n";
        Files.writeString(script.toPath(), content);
        return script;
    }

    private static String shellEscape(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private Map<String, String> fetchGitCredentials(String userId, String host) {
        try {
            WebClient client = webClientBuilder.baseUrl("http://sunshine-auth").build();
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
            log.warn("[WorkspaceLifecycle] git-credentials failed: {}", e.getMessage());
            return Map.of();
        }
    }

    private static String extractHost(String url) {
        try { return new URI(url).getHost(); } catch (Exception e) { return ""; }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    public void destroyWorkspaceSession(String tenantId, String workspaceId) {
        store.remove(tenantId, workspaceId).ifPresent(b -> {
            sandboxClient.closeSession(b.sessionId());
            log.info("[WorkspaceLifecycle] destroyed session={} ws={}", b.sessionId(), workspaceId);
        });
    }

    /** 同步共享 git 裸库：已克隆则 fetch --all 更新 refs，未克隆则重新 clone --mirror；用于刷新/重试 */
    public Map<String, Object> syncWorkspaceCode(String workspaceId, String userId) {
        AgentWorkspaceEntity ws = workspaceRepo.findById(workspaceId)
                .orElseThrow(() -> new IllegalStateException("工作区不存在: " + workspaceId));
        String hostDir = hostWorkspaceRoot + "/workspaces/" + workspaceId;
        Path repoPath = repoDir(workspaceId);
        String action;
        if (repoPath.toFile().exists() && new File(repoPath.toFile(), "objects").exists()) {
            try {
                Map<String, Object> result = gitService.gitFetchAll(workspaceId, userId, ws.getTenantId());
                action = "fetched";
                updateBindingCloneState(ws.getTenantId(), workspaceId, "done");
                log.info("[WorkspaceLifecycle] sync fetched ws={}", workspaceId);
                Map<String, Object> resp = new java.util.LinkedHashMap<>();
                resp.put("action", action);
                resp.put("output", result.get("output"));
                return resp;
            } catch (Exception e) {
                updateBindingCloneState(ws.getTenantId(), workspaceId, "failed:" + truncate(e.getMessage(), 120));
                throw new RuntimeException("git fetch failed: " + e.getMessage(), e);
            }
        } else {
            // 未克隆或 clone 失败残留 → 重新 clone --mirror
            deleteTreeQuietly(repoPath.toFile());
            try {
                Files.createDirectories(Path.of(hostDir));
                Files.createDirectories(repoPath.getParent());
                cloneMirrorRepo(ws.getRepoUrl(), userId, repoPath);
                runFixPerms(Path.of(hostDir));
                runFixPerms(repoPath);
                action = "cloned";
                updateBindingCloneState(ws.getTenantId(), workspaceId, "done");
                log.info("[WorkspaceLifecycle] sync cloned ws={}", workspaceId);
                Map<String, Object> resp = new java.util.LinkedHashMap<>();
                resp.put("action", action);
                return resp;
            } catch (Exception e) {
                updateBindingCloneState(ws.getTenantId(), workspaceId, "failed:" + truncate(e.getMessage(), 120));
                throw new RuntimeException("git clone --mirror failed: " + e.getMessage(), e);
            }
        }
    }

    private void updateBindingCloneState(String tenantId, String workspaceId, String cloneState) {
        store.find(tenantId, workspaceId).ifPresent(b -> {
            store.save(b.withCloneState(cloneState));
        });
    }

    /** 共享裸库宿主路径：与 worktrees（workspaces/{wsId}）分离，避免挂载进 /workspace 暴露给 AI */
    private Path repoDir(String workspaceId) {
        return Path.of(hostWorkspaceRoot, "repos", workspaceId + ".git");
    }
}
