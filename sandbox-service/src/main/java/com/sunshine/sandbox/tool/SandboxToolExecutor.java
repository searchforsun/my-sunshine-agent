package com.sunshine.sandbox.tool;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.core.exception.FixedErrorCode;
import com.sunshine.common.sandbox.ToolInvokeResponse;
import com.sunshine.sandbox.config.SandboxProperties;
import com.sunshine.sandbox.docker.DockerCli;
import com.sunshine.sandbox.docker.ExecResult;
import com.sunshine.sandbox.docker.SandboxInvocationRegistry;
import com.sunshine.sandbox.exception.SandboxErrorCode;
import com.sunshine.sandbox.jail.PathJail;
import com.sunshine.sandbox.metrics.SandboxMetrics;
import com.sunshine.sandbox.session.SandboxSession;
import com.sunshine.sandbox.session.SandboxSessionStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class SandboxToolExecutor {

    static final int DEFAULT_MAX_CHARS = 200_000;
    static final int MAX_GREP_HITS = 200;

    private final SandboxSessionStore store;
    private final DockerCli dockerCli;
    private final SandboxProperties properties;
    private final SandboxMetrics metrics;
    private final SandboxInvocationRegistry invocationRegistry;

    public ToolInvokeResponse invoke(String sessionId, String name, Map<String, Object> body) {
        return invoke(sessionId, name, body, null);
    }

    public ToolInvokeResponse invoke(
            String sessionId, String name, Map<String, Object> body, String invocationId) {
        long startNanos = System.nanoTime();
        String tool = name != null ? name : "unknown";
        String invId = StringUtils.hasText(invocationId) ? invocationId.strip() : null;
        try {
            ToolInvokeResponse response = doInvoke(sessionId, name, body, invId);
            recordMetrics(tool, response != null && response.ok(), startNanos);
            return response;
        } catch (RuntimeException e) {
            recordMetrics(tool, false, startNanos);
            throw e;
        } finally {
            if (invId != null
                    && (SandboxToolNames.GLOB.equals(name) || SandboxToolNames.GREP.equals(name))) {
                invocationRegistry.unbind(invId);
            }
        }
    }

    private void recordMetrics(String tool, boolean ok, long startNanos) {
        if (metrics != null) {
            metrics.recordToolInvoke(tool, ok, startNanos);
        }
    }

    private ToolInvokeResponse doInvoke(
            String sessionId, String name, Map<String, Object> body, String invocationId) {
        SandboxSession session = store.get(sessionId)
                .orElseThrow(() -> new BizException(SandboxErrorCode.SESSION_NOT_FOUND));
        Map<String, Object> args = body != null ? body : Map.of();
        return switch (name) {
            case SandboxToolNames.READ -> read(session, args);
            case SandboxToolNames.WRITE -> write(session, args);
            case SandboxToolNames.EDIT -> edit(session, args);
            case SandboxToolNames.GLOB -> glob(session, args, invocationId);
            case SandboxToolNames.GREP -> grep(session, args, invocationId);
            case SandboxToolNames.EXEC -> exec(session, args, invocationId);
            case null, default -> throw new BizException(SandboxErrorCode.TOOL_UNKNOWN);
        };
    }

    private ToolInvokeResponse exec(SandboxSession session, Map<String, Object> args, String invocationId) {
        String command = requireString(args, "command");
        String blocked = SandboxExecGuard.denyReason(command);
        if (blocked != null) {
            throw detailError(SandboxErrorCode.EXEC_BLOCKED,
                    "command blocked: " + blocked + "; command=" + clip(command, 120));
        }
        Path cwd;
        try {
            cwd = PathJail.resolveCwd(optionalString(args, "cwd"));
        } catch (IllegalArgumentException e) {
            throw badPath(e.getMessage());
        }
        int timeoutSec = resolveTimeoutSec(session, asInteger(args.get("timeout_sec")));
        ExecResult result = dockerCli.exec(
                session.containerName(),
                cwd.toString(),
                List.of("sh", "-lc", command),
                Duration.ofSeconds(timeoutSec),
                invocationId);
        String output = combineOutput(result.stdout(), result.stderr());
        if (result.exitCode() == -1 && output.toLowerCase().contains("cancelled")) {
            return new ToolInvokeResponse(false, "cancelled", -1, Map.of("cancelled", true));
        }
        if (result.exitCode() == -1 && output.toLowerCase().contains("timeout")) {
            return new ToolInvokeResponse(false, output, -1, Map.of());
        }
        return new ToolInvokeResponse(result.exitCode() == 0, output, result.exitCode(), Map.of());
    }

    private int resolveTimeoutSec(SandboxSession session, Integer override) {
        if (override != null && override > 0) {
            return override;
        }
        Integer policySec = session.policy() != null ? session.policy().timeoutSec() : null;
        if (policySec != null && policySec > 0) {
            return policySec;
        }
        return properties.getDocker().getDefaultTimeoutSec();
    }

    private static String combineOutput(String stdout, String stderr) {
        String out = stdout != null ? stdout : "";
        String err = stderr != null ? stderr : "";
        if (err.isEmpty()) {
            return out;
        }
        if (out.isEmpty()) {
            return err;
        }
        return out + err;
    }

    private ToolInvokeResponse read(SandboxSession session, Map<String, Object> args) {
        String path = requireString(args, "path");
        Path host = toHostSafe(session, path, false);
        if (Files.isDirectory(host)) {
            throw detailError(SandboxErrorCode.NOT_A_FILE,
                    "path is a directory, not a file: " + path + "; use sandbox__glob to list");
        }
        if (!Files.isRegularFile(host)) {
            throw badPath("file not found: " + path);
        }
        try {
            String content = Files.readString(host, StandardCharsets.UTF_8);
            Integer offset = asInteger(args.get("offset"));
            Integer limit = asInteger(args.get("limit"));
            if (offset != null || limit != null) {
                content = sliceLines(content, offset, limit);
            }
            Map<String, Object> meta = new HashMap<>();
            if (content.length() > DEFAULT_MAX_CHARS) {
                content = content.substring(0, DEFAULT_MAX_CHARS);
                meta.put("truncated", true);
            }
            return new ToolInvokeResponse(true, content, null, meta.isEmpty() ? Map.of() : meta);
        } catch (IOException e) {
            throw new IllegalStateException("read failed: " + path, e);
        }
    }

    private ToolInvokeResponse write(SandboxSession session, Map<String, Object> args) {
        String path = requireString(args, "path");
        String content = args.get("content") != null ? String.valueOf(args.get("content")) : "";
        Path host = toHostSafe(session, path, true);
        if (Files.exists(host)) {
            throw detailError(SandboxErrorCode.WRITE_ALREADY_EXISTS,
                    "file already exists: " + path + "; use sandbox__edit or choose another path");
        }
        try {
            Path parent = host.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(host, content, StandardCharsets.UTF_8);
            return new ToolInvokeResponse(true, "", null, Map.of());
        } catch (IOException e) {
            throw new IllegalStateException("write failed: " + path, e);
        }
    }

    private ToolInvokeResponse edit(SandboxSession session, Map<String, Object> args) {
        String path = requireString(args, "path");
        String oldString = requireString(args, "old_string");
        String newString = args.get("new_string") != null ? String.valueOf(args.get("new_string")) : "";
        Path host = toHostSafe(session, path, true);
        if (Files.isDirectory(host)) {
            throw detailError(SandboxErrorCode.NOT_A_FILE,
                    "path is a directory, not a file: " + path);
        }
        if (!Files.isRegularFile(host)) {
            throw badPath("file not found: " + path + "; create with sandbox__write first");
        }
        try {
            String content = Files.readString(host, StandardCharsets.UTF_8);
            int count = countOccurrences(content, oldString);
            if (count == 0) {
                throw detailError(SandboxErrorCode.EDIT_NOT_FOUND,
                        "old_string not found in " + path);
            }
            if (count != 1) {
                throw detailError(SandboxErrorCode.EDIT_NOT_UNIQUE,
                        "old_string not unique in " + path + " (matches=" + count + ")");
            }
            String updated = content.replace(oldString, newString);
            Files.writeString(host, updated, StandardCharsets.UTF_8);
            return new ToolInvokeResponse(true, "", null, Map.of());
        } catch (IOException e) {
            throw new IllegalStateException("edit failed: " + path, e);
        }
    }

    private ToolInvokeResponse glob(
            SandboxSession session, Map<String, Object> args, String invocationId) {
        AtomicBoolean cancelled = invocationId != null
                ? invocationRegistry.bindFlag(invocationId) : new AtomicBoolean(false);
        String pattern = requireString(args, "pattern");
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        List<String> hits = new ArrayList<>();
        for (SearchRoot root : resolveSearchRoots(session, optionalString(args, "path"))) {
            if (cancelled.get()) {
                return cancelledResponse();
            }
            collectGlobHits(session, root, matcher, hits, cancelled);
            if (cancelled.get()) {
                return cancelledResponse();
            }
        }
        Collections.sort(hits);
        return new ToolInvokeResponse(true, String.join("\n", hits), null, Map.of());
    }

    private ToolInvokeResponse grep(
            SandboxSession session, Map<String, Object> args, String invocationId) {
        AtomicBoolean cancelled = invocationId != null
                ? invocationRegistry.bindFlag(invocationId) : new AtomicBoolean(false);
        String patternStr = requireString(args, "pattern");
        Pattern regex;
        try {
            regex = Pattern.compile(patternStr);
        } catch (PatternSyntaxException e) {
            throw new BizException(SandboxErrorCode.PATTERN_INVALID);
        }
        PathMatcher fileGlob = null;
        String globFilter = optionalString(args, "glob");
        if (globFilter != null) {
            fileGlob = FileSystems.getDefault().getPathMatcher("glob:" + globFilter);
        }
        List<String> hits = new ArrayList<>();
        boolean hitLimit = false;
        for (SearchRoot root : resolveSearchRoots(session, optionalString(args, "path"))) {
            if (cancelled.get()) {
                return cancelledResponse();
            }
            hitLimit = collectGrepHits(session, root, regex, fileGlob, hits, cancelled);
            if (cancelled.get()) {
                return cancelledResponse();
            }
            if (hitLimit) {
                break;
            }
        }
        Map<String, Object> meta = hitLimit ? Map.of("hitLimit", true) : Map.of();
        return new ToolInvokeResponse(true, String.join("\n", hits), null, meta);
    }

    private static ToolInvokeResponse cancelledResponse() {
        return new ToolInvokeResponse(false, "cancelled", -1, Map.of("cancelled", true));
    }

    private static void collectGlobHits(
            SandboxSession session,
            SearchRoot root,
            PathMatcher matcher,
            List<String> hits,
            AtomicBoolean cancelled) {
        if (!Files.exists(root.host()) || cancelled.get()) {
            return;
        }
        if (Files.isRegularFile(root.host())) {
            Path rel = root.walkBase().relativize(root.host());
            if (matcher.matches(rel) || matcher.matches(root.host().getFileName())) {
                hits.add(HostPathResolver.toContainer(session, root.host()));
            }
            return;
        }
        try (Stream<Path> walk = Files.walk(root.host())) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                if (cancelled.get()) {
                    return;
                }
                Path rel = root.walkBase().relativize(p);
                if (matcher.matches(rel) || matcher.matches(p.getFileName())) {
                    hits.add(HostPathResolver.toContainer(session, p));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("glob failed", e);
        }
    }

    /** @return true if hit limit reached */
    private static boolean collectGrepHits(
            SandboxSession session,
            SearchRoot root,
            Pattern regex,
            PathMatcher fileGlob,
            List<String> hits,
            AtomicBoolean cancelled) {
        if (!Files.exists(root.host()) || cancelled.get()) {
            return false;
        }
        if (Files.isRegularFile(root.host())) {
            return grepFile(session, root.host(), root.walkBase(), regex, fileGlob, hits);
        }
        try (Stream<Path> walk = Files.walk(root.host())) {
            List<Path> files = walk.filter(Files::isRegularFile).sorted().toList();
            for (Path p : files) {
                if (cancelled.get()) {
                    return false;
                }
                if (grepFile(session, p, root.walkBase(), regex, fileGlob, hits)) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            throw new IllegalStateException("grep failed", e);
        }
    }

    private static boolean grepFile(
            SandboxSession session,
            Path hostFile,
            Path walkBase,
            Pattern regex,
            PathMatcher fileGlob,
            List<String> hits) {
        if (fileGlob != null) {
            Path rel = walkBase.relativize(hostFile);
            if (!fileGlob.matches(rel) && !fileGlob.matches(hostFile.getFileName())) {
                return false;
            }
        }
        String containerPath = HostPathResolver.toContainer(session, hostFile);
        try {
            List<String> lines = Files.readAllLines(hostFile, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (regex.matcher(line).find()) {
                    hits.add(containerPath + ":" + (i + 1) + ":" + line);
                    if (hits.size() >= MAX_GREP_HITS) {
                        return true;
                    }
                }
            }
            return false;
        } catch (IOException e) {
            throw new IllegalStateException("grep failed: " + containerPath, e);
        }
    }

    /**
     * path 缺省：同时搜 /skills 与 /workspace；否则解析为 jail 内单根（文件或目录）。
     */
    private static List<SearchRoot> resolveSearchRoots(SandboxSession session, String pathOpt) {
        if (pathOpt == null) {
            Path skill = session.hostRoot().resolve("skills").toAbsolutePath().normalize();
            Path workspace = session.hostRoot().resolve("workspace").toAbsolutePath().normalize();
            return List.of(new SearchRoot(skill, skill), new SearchRoot(workspace, workspace));
        }
        Path host = toHostSafe(session, pathOpt, false);
        Path walkBase = host;
        if (Files.isRegularFile(host) && host.getParent() != null) {
            walkBase = host.getParent();
        }
        return List.of(new SearchRoot(host, walkBase));
    }

    private record SearchRoot(Path host, Path walkBase) {}

    private static Path toHostSafe(SandboxSession session, String containerPath, boolean forWrite) {
        try {
            return HostPathResolver.toHost(session, containerPath, forWrite);
        } catch (IllegalArgumentException e) {
            throw badPath(e.getMessage());
        }
    }

    private static BizException badPath(String detail) {
        return detailError(SandboxErrorCode.FILE_PATH_INVALID, detail);
    }

    private static BizException detailError(SandboxErrorCode code, String detail) {
        return new BizException(new FixedErrorCode(code.getCode(), code.getKey(), detail));
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        String t = text.strip();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    private static String requireString(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw badPath(key + " required");
        }
        return String.valueOf(v);
    }

    private static String optionalString(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            return null;
        }
        return String.valueOf(v);
    }

    private static Integer asInteger(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(String.valueOf(v));
    }

    /** offset 为 1-based 起始行；limit 为最多行数 */
    static String sliceLines(String content, Integer offset, Integer limit) {
        List<String> lines = content.lines().toList();
        int start = offset != null && offset > 0 ? offset - 1 : 0;
        if (start >= lines.size()) {
            return "";
        }
        int end = limit != null && limit >= 0 ? Math.min(lines.size(), start + limit) : lines.size();
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            sb.append(lines.get(i)).append('\n');
        }
        return sb.toString();
    }

    static int countOccurrences(String content, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int from = 0;
        while (true) {
            int idx = content.indexOf(needle, from);
            if (idx < 0) {
                break;
            }
            count++;
            from = idx + needle.length();
        }
        return count;
    }
}
