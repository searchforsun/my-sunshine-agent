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
    private final WebClient.Builder webClientBuilder;

    @Value("${sandbox.host-workspace-root:/var/lib/sunshine-sandbox}")
    private String hostWorkspaceRoot;

    @Value("${auth-service.base-url:http://localhost:8210}")
    private String authBaseUrl;

    public String ensureWorkspaceSession(String workspaceId, String userId, String tenantId) {
        WorkspaceSandboxBinding binding = store.find(tenantId, workspaceId).orElse(null);
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
        Path hostPath = Path.of(hostDir, "main");
        String cloneState = "done";
        try {
            cloneRepo(ws.getRepoUrl(), ws.getRepoBranch(), userId, hostPath);
        } catch (Exception e) {
            cloneState = "failed:" + truncate(e.getMessage(), 120);
            log.warn("[WorkspaceLifecycle] clone failed ws={}: {}", workspaceId, e.getMessage());
        }
        String sessionId = sandboxClient.createSession(new CreateSessionRequest(
                userId, tenantId, null, "workspace-" + workspaceId,
                fullSessionPolicy(ws), Map.of(), Map.of(hostDir, "/workspace")));
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
                List.of(), List.of());
    }

    private void cloneRepo(String repoUrl, String branch, String userId, Path target) {
        String host = extractHost(repoUrl);
        Map<String, String> cred = fetchGitCredentials(userId, host);
        String token = cred.getOrDefault("token", "");
        File dir = target.toFile();
        if (dir.exists() && new File(dir, ".git").exists()) {
            return;
        }
        dir.mkdirs();
        File askpassScript = null;
        try {
            ProcessBuilder pb;
            if (!token.isEmpty()) {
                askpassScript = writeAskpassScript(target.getParent(), token);
                pb = new ProcessBuilder(
                        "git", "clone", "--depth", "1", "--branch", branch, repoUrl, dir.getAbsolutePath());
                pb.environment().put("GIT_ASKPASS", askpassScript.getAbsolutePath());
                pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            } else {
                pb = new ProcessBuilder(
                        "git", "clone", "--depth", "1", "--branch", branch, repoUrl, dir.getAbsolutePath());
            }
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean done = p.waitFor(5, TimeUnit.MINUTES);
            if (!done) {
                p.destroyForcibly();
                throw new RuntimeException("git clone timeout after 5min");
            }
            String output = new String(p.getInputStream().readAllBytes());
            int code = p.exitValue();
            if (code != 0) throw new RuntimeException("git clone exit " + code + ": " + truncate(output, 200));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("git clone interrupted", e);
        } catch (IOException e) {
            throw new RuntimeException("git clone io error", e);
        } finally {
            if (askpassScript != null) askpassScript.delete();
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
            WebClient client = webClientBuilder.baseUrl(authBaseUrl).build();
            @SuppressWarnings("unchecked")
            var resp = (Map<String, Object>) client.get()
                    .uri(uriBuilder -> uriBuilder.path("/api/auth/git-credentials")
                            .queryParam("host", host).build())
                    .header("x-user-id", userId)
                    .retrieve().bodyToMono(Map.class).block();
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
}
