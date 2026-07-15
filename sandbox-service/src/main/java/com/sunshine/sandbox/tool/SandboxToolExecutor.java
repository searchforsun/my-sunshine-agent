package com.sunshine.sandbox.tool;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.core.exception.FixedErrorCode;
import com.sunshine.sandbox.api.ToolInvokeResponse;
import com.sunshine.sandbox.exception.SandboxErrorCode;
import com.sunshine.sandbox.session.SandboxSession;
import com.sunshine.sandbox.session.SandboxSessionStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SandboxToolExecutor {

    static final int DEFAULT_MAX_CHARS = 200_000;

    private final SandboxSessionStore store;

    public ToolInvokeResponse invoke(String sessionId, String name, Map<String, Object> body) {
        SandboxSession session = store.get(sessionId)
                .orElseThrow(() -> new BizException(SandboxErrorCode.SESSION_NOT_FOUND));
        Map<String, Object> args = body != null ? body : Map.of();
        return switch (name) {
            case SandboxToolNames.READ -> read(session, args);
            case SandboxToolNames.WRITE -> write(session, args);
            case SandboxToolNames.EDIT -> edit(session, args);
            case SandboxToolNames.GLOB, SandboxToolNames.GREP, SandboxToolNames.EXEC ->
                    throw new BizException(new FixedErrorCode(
                            SandboxErrorCode.TOOL_NOT_IMPLEMENTED.getCode(),
                            SandboxErrorCode.TOOL_NOT_IMPLEMENTED.getKey(),
                            name + " not implemented yet"));
            case null, default -> throw new BizException(SandboxErrorCode.TOOL_UNKNOWN);
        };
    }

    private ToolInvokeResponse read(SandboxSession session, Map<String, Object> args) {
        String path = requireString(args, "path");
        Path host = toHostSafe(session, path, false);
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
        if (!Files.isRegularFile(host)) {
            throw badPath("file not found: " + path);
        }
        try {
            String content = Files.readString(host, StandardCharsets.UTF_8);
            int count = countOccurrences(content, oldString);
            if (count == 0) {
                throw new BizException(SandboxErrorCode.EDIT_NOT_FOUND);
            }
            if (count != 1) {
                throw new BizException(SandboxErrorCode.EDIT_NOT_UNIQUE);
            }
            String updated = content.replace(oldString, newString);
            Files.writeString(host, updated, StandardCharsets.UTF_8);
            return new ToolInvokeResponse(true, "", null, Map.of());
        } catch (IOException e) {
            throw new IllegalStateException("edit failed: " + path, e);
        }
    }

    private static Path toHostSafe(SandboxSession session, String containerPath, boolean forWrite) {
        try {
            return HostPathResolver.toHost(session, containerPath, forWrite);
        } catch (IllegalArgumentException e) {
            throw badPath(e.getMessage());
        }
    }

    private static BizException badPath(String detail) {
        return new BizException(new FixedErrorCode(
                SandboxErrorCode.FILE_PATH_INVALID.getCode(),
                SandboxErrorCode.FILE_PATH_INVALID.getKey(),
                detail));
    }

    private static String requireString(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw badPath(key + " required");
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
