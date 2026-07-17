package com.sunshine.sandbox.fs;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.sandbox.FsContentDto;
import com.sunshine.common.sandbox.FsNodeDto;
import com.sunshine.sandbox.exception.SandboxErrorCode;
import com.sunshine.sandbox.jail.PathJail;
import com.sunshine.sandbox.session.SandboxSession;
import com.sunshine.sandbox.session.SandboxSessionStore;
import com.sunshine.sandbox.tool.HostPathResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** 宿主机浏览会话 /workspace 与 /skills（不经 docker exec） */
@Service
@RequiredArgsConstructor
public class SandboxFsService {

    private static final int DEFAULT_MAX_CHARS = 200_000;

    private final SandboxSessionStore store;

    public FsNodeDto.FsListResponse list(String sessionId, String rawPath) {
        SandboxSession session = requireSession(sessionId);
        String containerPath = normalizeBrowsePath(rawPath);
        Path host = HostPathResolver.toHost(session, containerPath, false);
        if (!Files.exists(host)) {
            throw new BizException(SandboxErrorCode.FILE_NOT_FOUND);
        }
        if (!Files.isDirectory(host)) {
            throw new BizException(SandboxErrorCode.FILE_PATH_INVALID);
        }
        List<FsNodeDto> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.list(host)) {
            stream.sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(child -> {
                        try {
                            String name = child.getFileName().toString();
                            String childContainer = HostPathResolver.toContainer(session, child);
                            if (Files.isDirectory(child)) {
                                entries.add(FsNodeDto.dir(name, childContainer));
                            } else {
                                long size = Files.size(child);
                                entries.add(FsNodeDto.file(name, childContainer, size));
                            }
                        } catch (IOException ignored) {
                            // skip unreadable
                        }
                    });
        } catch (IOException e) {
            throw new BizException(SandboxErrorCode.FILE_PATH_INVALID);
        }
        return new FsNodeDto.FsListResponse(containerPath, entries);
    }

    public FsContentDto readContent(String sessionId, String rawPath, int maxChars) {
        SandboxSession session = requireSession(sessionId);
        String containerPath = normalizeBrowsePath(rawPath);
        if (containerPath.equals(PathJail.WORKSPACE.toString())
                || containerPath.equals(PathJail.SKILLS.toString())) {
            throw new BizException(SandboxErrorCode.FILE_PATH_INVALID);
        }
        Path host = HostPathResolver.toHost(session, containerPath, false);
        if (!Files.exists(host) || Files.isDirectory(host)) {
            throw new BizException(SandboxErrorCode.FILE_NOT_FOUND);
        }
        int limit = maxChars > 0 ? maxChars : DEFAULT_MAX_CHARS;
        try {
            byte[] bytes = Files.readAllBytes(host);
            if (looksBinary(bytes)) {
                return new FsContentDto(containerPath, "", false, true);
            }
            String full;
            try {
                full = decodeUtf8(bytes);
            } catch (CharacterCodingException e) {
                return new FsContentDto(containerPath, "", false, true);
            }
            if (full.length() <= limit) {
                return new FsContentDto(containerPath, full, false, false);
            }
            return new FsContentDto(containerPath, full.substring(0, limit), true, false);
        } catch (IOException e) {
            throw new BizException(SandboxErrorCode.FILE_PATH_INVALID);
        }
    }

    public boolean alive(String sessionId) {
        return store.get(sessionId).isPresent();
    }

    private SandboxSession requireSession(String sessionId) {
        return store.get(sessionId).orElseThrow(() ->
                new BizException(SandboxErrorCode.SESSION_NOT_FOUND));
    }

    static String normalizeBrowsePath(String raw) {
        String path = StringUtils.hasText(raw) ? raw.strip() : PathJail.WORKSPACE.toString();
        Path jailed;
        try {
            jailed = PathJail.resolveBrowse(path);
        } catch (IllegalArgumentException e) {
            throw new BizException(SandboxErrorCode.FILE_PATH_INVALID);
        }
        return jailed.toString().replace('\\', '/');
    }

    private static boolean looksBinary(byte[] bytes) {
        int n = Math.min(bytes.length, 8000);
        for (int i = 0; i < n; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }
}
