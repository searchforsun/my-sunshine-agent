package com.sunshine.sandbox.session;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.core.exception.FixedErrorCode;
import com.sunshine.sandbox.api.CreateSessionRequest;
import com.sunshine.sandbox.api.SandboxPolicyDto;
import com.sunshine.sandbox.config.SandboxProperties;
import com.sunshine.sandbox.docker.DockerCli;
import com.sunshine.sandbox.exception.SandboxErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SandboxSessionService {

    private static final int SANDBOX_UID = 10001;

    private final DockerCli dockerCli;
    private final SandboxSessionStore store;
    private final SandboxProperties properties;

    public String create(CreateSessionRequest req) {
        SandboxPolicyDto policy = req.policy() != null ? req.policy()
                : new SandboxPolicyDto(null, null, null, null, null, null, null);
        rejectNetworkAllow(policy.networkAllow());
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        Path hostRoot = Path.of(properties.getDocker().getHostDataRoot(), sessionId);
        Path hostSkill = hostRoot.resolve("skill");
        Path hostWorkspace = hostRoot.resolve("workspace");
        try {
            Files.createDirectories(hostSkill);
            Files.createDirectories(hostWorkspace);
            writeSkillFiles(hostSkill, req.skillFiles());
            writeWorkspaceFiles(hostWorkspace, req.workspaceFiles());
            preparePermissions(hostSkill, hostWorkspace);
        } catch (IOException e) {
            deleteTreeQuietly(hostRoot);
            throw new IllegalStateException("failed to prepare session dirs: " + sessionId, e);
        }
        String image = firstNonBlank(policy.image(), properties.getDocker().getDefaultImage());
        int memoryMb = policy.memoryMb() != null ? policy.memoryMb() : properties.getDocker().getDefaultMemoryMb();
        String cpus = policy.cpus() != null
                ? String.valueOf(policy.cpus())
                : properties.getDocker().getDefaultCpus();
        String containerName = "sunshine-sb-" + sessionId.substring(0, Math.min(12, sessionId.length()));
        List<String> args = buildRunArgs(containerName, image, memoryMb, cpus, hostSkill, hostWorkspace);
        try {
            String containerId = dockerCli.runDetached(args);
            String storedId = containerId != null && !containerId.isBlank() ? containerId.trim() : containerName;
            SandboxPolicyDto resolved = new SandboxPolicyDto(
                    policy.runtime() != null ? policy.runtime() : "docker",
                    image,
                    policy.timeoutSec() != null ? policy.timeoutSec() : properties.getDocker().getDefaultTimeoutSec(),
                    memoryMb,
                    Double.valueOf(cpus),
                    policy.networkAllow() != null ? policy.networkAllow() : List.of(),
                    policy.execReadonlyAllow());
            store.put(new SandboxSession(sessionId, storedId, hostRoot, resolved));
            log.info("sandbox session created id={} container={}", sessionId, storedId);
            return sessionId;
        } catch (RuntimeException e) {
            deleteTreeQuietly(hostRoot);
            throw e;
        }
    }

    public void close(String sessionId) {
        SandboxSession session = store.remove(sessionId);
        if (session == null) {
            throw new BizException(SandboxErrorCode.SESSION_NOT_FOUND);
        }
        try {
            dockerCli.removeForce(session.containerName());
        } catch (RuntimeException e) {
            log.warn("docker remove failed for session {}: {}", sessionId, e.getMessage());
        }
        deleteTreeQuietly(session.hostRoot());
        log.info("sandbox session closed id={}", sessionId);
    }

    private static void rejectNetworkAllow(List<String> networkAllow) {
        if (networkAllow != null && !networkAllow.isEmpty()) {
            throw new BizException(SandboxErrorCode.NETWORK_ALLOW_NOT_SUPPORTED);
        }
    }

    private List<String> buildRunArgs(
            String containerName, String image, int memoryMb, String cpus, Path hostSkill, Path hostWorkspace) {
        List<String> args = new ArrayList<>();
        args.add("run");
        args.add("-d");
        args.add("--name");
        args.add(containerName);
        args.add("--network");
        args.add("none");
        args.add("--read-only");
        args.add("--tmpfs");
        args.add("/tmp");
        args.add("-m");
        args.add(memoryMb + "m");
        args.add("--cpus");
        args.add(cpus);
        args.add("--user");
        args.add(SANDBOX_UID + ":" + SANDBOX_UID);
        args.add("-v");
        args.add(hostSkill.toAbsolutePath() + ":/skill:ro");
        args.add("-v");
        args.add(hostWorkspace.toAbsolutePath() + ":/workspace");
        args.add("--cap-drop");
        args.add("ALL");
        args.add(image);
        args.add("sleep");
        args.add("infinity");
        return args;
    }

    private void writeSkillFiles(Path hostSkill, Map<String, String> skillFiles) throws IOException {
        if (skillFiles == null || skillFiles.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> e : skillFiles.entrySet()) {
            String key = requireSafeRelative(e.getKey(), "skill");
            if (!key.startsWith("scripts/") && !key.startsWith("references/")) {
                throw new BizException(SandboxErrorCode.SKILL_FILE_PATH_INVALID);
            }
            Path target = hostSkill.resolve(key).normalize();
            if (!target.startsWith(hostSkill)) {
                throw badPath("skill file path escapes jail: " + key);
            }
            Files.createDirectories(target.getParent());
            Files.writeString(target, e.getValue() != null ? e.getValue() : "", StandardCharsets.UTF_8);
        }
    }

    private void writeWorkspaceFiles(Path hostWorkspace, Map<String, String> workspaceFiles) throws IOException {
        if (workspaceFiles == null || workspaceFiles.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> e : workspaceFiles.entrySet()) {
            String key = requireSafeRelative(e.getKey(), "workspace");
            Path target = hostWorkspace.resolve(key).normalize();
            if (!target.startsWith(hostWorkspace)) {
                throw badPath("workspace file path escapes jail: " + key);
            }
            Files.createDirectories(target.getParent());
            Files.writeString(target, e.getValue() != null ? e.getValue() : "", StandardCharsets.UTF_8);
        }
    }

    private static String requireSafeRelative(String key, String label) {
        if (key == null || key.isBlank()) {
            throw badPath(label + " file key required");
        }
        String normalized = key.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("..")) {
            throw badPath(label + " file key invalid: " + key);
        }
        return normalized;
    }

    private static BizException badPath(String detail) {
        return new BizException(new FixedErrorCode(
                SandboxErrorCode.FILE_PATH_INVALID.getCode(),
                SandboxErrorCode.FILE_PATH_INVALID.getKey(),
                detail));
    }

    private void preparePermissions(Path hostSkill, Path hostWorkspace) {
        try {
            Set<PosixFilePermission> skillPerms = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(hostSkill, skillPerms);
            // v1：chown 可能需 root；失败则 workspace 0777 便于容器 uid 写入
            try {
                Files.setPosixFilePermissions(hostWorkspace, PosixFilePermissions.fromString("rwxrwxrwx"));
            } catch (UnsupportedOperationException ignored) {
                // non-posix FS in tests
            }
        } catch (UnsupportedOperationException | IOException e) {
            log.debug("posix permission skip: {}", e.getMessage());
        }
    }

    private static void deleteTreeQuietly(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("failed to delete session dir {}: {}", root, e.getMessage());
        }
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback;
    }
}
