package com.sunshine.sandbox.session;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.core.exception.FixedErrorCode;
import com.sunshine.sandbox.api.CreateSessionRequest;
import com.sunshine.sandbox.api.SandboxPolicyDto;
import com.sunshine.sandbox.config.SandboxProperties;
import com.sunshine.sandbox.docker.DockerCli;
import com.sunshine.sandbox.docker.EgressProxyManager;
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
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class SandboxSessionService {

    private static final int SANDBOX_UID = 10001;
    /** registry/name:tag 或 name@sha256:...；禁止空格与 shell 元字符 */
    private static final Pattern SAFE_IMAGE = Pattern.compile(
            "^[a-zA-Z0-9][a-zA-Z0-9._\\-/]*(:[a-zA-Z0-9._\\-]+)?(@sha256:[a-fA-F0-9]{64})?$");
    /** skillId：字母数字、点、下划线、短横线 */
    private static final Pattern SAFE_SKILL_ID = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._\\-]{0,127}$");

    private final DockerCli dockerCli;
    private final SandboxSessionStore store;
    private final SandboxProperties properties;
    private final EgressProxyManager egressProxyManager;

    public String create(CreateSessionRequest req) {
        SandboxPolicyDto policy = req.policy() != null ? req.policy()
                : new SandboxPolicyDto(null, null, null, null, null, null, null);
        List<String> networkAllow = policy.networkAllow() != null ? policy.networkAllow() : List.of();
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        Path hostRoot = Path.of(properties.getDocker().getHostDataRoot(), sessionId);
        Path hostSkills = hostRoot.resolve("skills");
        Path hostWorkspace = hostRoot.resolve("workspace");
        try {
            Files.createDirectories(hostSkills);
            Files.createDirectories(hostWorkspace);
            // create 仅建空目录；Skill 物料走 mountSkill 懒挂载
            writeWorkspaceFiles(hostWorkspace, req.workspaceFiles());
            preparePermissions(hostSkills, hostWorkspace);
        } catch (IOException e) {
            deleteTreeQuietly(hostRoot);
            throw new IllegalStateException("failed to prepare session dirs: " + sessionId, e);
        }
        String image = firstNonBlank(policy.image(), properties.getDocker().getDefaultImage());
        validateImage(image);
        int memoryMb = policy.memoryMb() != null ? policy.memoryMb() : properties.getDocker().getDefaultMemoryMb();
        String cpus = policy.cpus() != null
                ? String.valueOf(policy.cpus())
                : properties.getDocker().getDefaultCpus();
        String containerName = "sunshine-sb-" + sessionId.substring(0, Math.min(12, sessionId.length()));
        List<String> args = buildRunArgs(
                containerName, image, memoryMb, cpus, hostSkills, hostWorkspace, networkAllow);
        String storedId = null;
        boolean dockerStarted = false;
        try {
            String containerId = dockerCli.runDetached(args);
            dockerStarted = true;
            storedId = containerId != null && !containerId.isBlank() ? containerId.trim() : containerName;
            SandboxPolicyDto resolved = new SandboxPolicyDto(
                    policy.runtime() != null ? policy.runtime() : "docker",
                    image,
                    policy.timeoutSec() != null ? policy.timeoutSec() : properties.getDocker().getDefaultTimeoutSec(),
                    memoryMb,
                    Double.valueOf(cpus),
                    networkAllow,
                    policy.execReadonlyAllow());
            store.put(new SandboxSession(sessionId, storedId, hostRoot, resolved));
            log.info("sandbox session created id={} container={}", sessionId, storedId);
            return sessionId;
        } catch (RuntimeException e) {
            if (dockerStarted) {
                String toRemove = storedId != null ? storedId : containerName;
                try {
                    dockerCli.removeForce(toRemove);
                } catch (RuntimeException rmEx) {
                    log.warn("docker remove after create failure failed for {}: {}", toRemove, rmEx.getMessage());
                }
            }
            deleteTreeQuietly(hostRoot);
            throw e;
        }
    }

    /** 懒挂载 Skill 物料到 /skills/{skillId}/（覆盖同 id） */
    public void mountSkill(String sessionId, String skillId, Map<String, String> skillFiles) {
        SandboxSession session = store.get(sessionId).orElseThrow(() ->
                new BizException(SandboxErrorCode.SESSION_NOT_FOUND));
        String id = requireSafeSkillId(skillId);
        Path hostSkillRoot = session.hostRoot().resolve("skills").resolve(id).normalize();
        Path hostSkills = session.hostRoot().resolve("skills").toAbsolutePath().normalize();
        if (!hostSkillRoot.toAbsolutePath().normalize().startsWith(hostSkills)) {
            throw new BizException(SandboxErrorCode.SKILL_ID_INVALID);
        }
        try {
            if (Files.exists(hostSkillRoot)) {
                deleteTreeQuietly(hostSkillRoot);
            }
            Files.createDirectories(hostSkillRoot);
            writeSkillFiles(hostSkillRoot, skillFiles);
            prepareSkillDirPerms(hostSkillRoot);
        } catch (IOException e) {
            throw new IllegalStateException("failed to mount skill " + id + " on session " + sessionId, e);
        }
        log.info("sandbox skill mounted session={} skillId={}", sessionId, id);
    }

    /** 拒绝空镜像、以 `-` 开头（CLI 注入）及非法字符的 image ref */
    static void validateImage(String image) {
        if (image == null || image.isBlank()) {
            throw new BizException(SandboxErrorCode.IMAGE_INVALID);
        }
        String trimmed = image.trim();
        if (trimmed.startsWith("-") || !SAFE_IMAGE.matcher(trimmed).matches()) {
            throw new BizException(SandboxErrorCode.IMAGE_INVALID);
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

    /** 停机：保留会话元数据与宿主机目录，仅 docker stop */
    public void stop(String sessionId) {
        SandboxSession session = store.get(sessionId).orElseThrow(() ->
                new BizException(SandboxErrorCode.SESSION_NOT_FOUND));
        try {
            dockerCli.stop(session.containerName());
        } catch (RuntimeException e) {
            log.warn("docker stop failed for session {}: {}", sessionId, e.getMessage());
            throw e;
        }
        log.info("sandbox session stopped id={}", sessionId);
    }

    /** 开机：docker start；已 running 则 no-op */
    public void start(String sessionId) {
        SandboxSession session = store.get(sessionId).orElseThrow(() ->
                new BizException(SandboxErrorCode.SESSION_NOT_FOUND));
        try {
            dockerCli.start(session.containerName());
        } catch (RuntimeException e) {
            log.warn("docker start failed for session {}: {}", sessionId, e.getMessage());
            throw e;
        }
        log.info("sandbox session started id={}", sessionId);
    }

    public boolean isRunning(String sessionId) {
        return store.get(sessionId)
                .map(s -> dockerCli.isRunning(s.containerName()))
                .orElse(false);
    }

    private List<String> buildRunArgs(
            String containerName,
            String image,
            int memoryMb,
            String cpus,
            Path hostSkills,
            Path hostWorkspace,
            List<String> networkAllow) {
        List<String> args = new ArrayList<>();
        args.add("run");
        args.add("-d");
        args.add("--name");
        args.add(containerName);
        boolean withNet = networkAllow != null && !networkAllow.isEmpty();
        if (withNet) {
            egressProxyManager.ensureRunning(networkAllow);
            args.add("--network");
            args.add(EgressProxyManager.NETWORK_NAME);
            String proxy = egressProxyManager.proxyUrl();
            args.add("-e");
            args.add("HTTP_PROXY=" + proxy);
            args.add("-e");
            args.add("HTTPS_PROXY=" + proxy);
            args.add("-e");
            args.add("NO_PROXY=localhost,127.0.0.1");
        } else {
            args.add("--network");
            args.add("none");
        }
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
        args.add(hostSkills.toAbsolutePath() + ":/skills:ro");
        args.add("-v");
        args.add(hostWorkspace.toAbsolutePath() + ":/workspace");
        args.add("--cap-drop");
        args.add("ALL");
        args.add(image);
        args.add("sleep");
        args.add("infinity");
        return args;
    }

    private void writeSkillFiles(Path hostSkillRoot, Map<String, String> skillFiles) throws IOException {
        if (skillFiles == null || skillFiles.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> e : skillFiles.entrySet()) {
            String key = requireSafeRelative(e.getKey(), "skill");
            if (!key.startsWith("scripts/") && !key.startsWith("references/")
                    && !"SKILL.md".equalsIgnoreCase(key)) {
                throw new BizException(SandboxErrorCode.SKILL_FILE_PATH_INVALID);
            }
            // 统一落盘为 SKILL.md，避免大小写不一致
            if ("SKILL.md".equalsIgnoreCase(key)) {
                key = "SKILL.md";
            }
            Path target = hostSkillRoot.resolve(key).normalize();
            if (!target.startsWith(hostSkillRoot)) {
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

    private static String requireSafeSkillId(String skillId) {
        if (skillId == null || skillId.isBlank() || !SAFE_SKILL_ID.matcher(skillId.strip()).matches()) {
            throw new BizException(SandboxErrorCode.SKILL_ID_INVALID);
        }
        return skillId.strip();
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

    private void preparePermissions(Path hostSkills, Path hostWorkspace) {
        try {
            Set<PosixFilePermission> skillPerms = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(hostSkills, skillPerms);
            try {
                Files.setPosixFilePermissions(hostWorkspace, PosixFilePermissions.fromString("rwxrwxrwx"));
            } catch (UnsupportedOperationException ignored) {
                // non-posix FS in tests
            }
        } catch (UnsupportedOperationException | IOException e) {
            log.debug("posix permission skip: {}", e.getMessage());
        }
    }

    private void prepareSkillDirPerms(Path hostSkillRoot) {
        try {
            Set<PosixFilePermission> skillPerms = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(hostSkillRoot, skillPerms);
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
