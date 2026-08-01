package com.sunshine.sandbox.tool;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.sandbox.SandboxPolicy;
import com.sunshine.common.sandbox.ToolInvokeResponse;
import com.sunshine.sandbox.config.SandboxProperties;
import com.sunshine.sandbox.docker.DockerCli;
import com.sunshine.sandbox.docker.ExecResult;
import com.sunshine.sandbox.docker.SandboxInvocationRegistry;
import com.sunshine.sandbox.exception.SandboxErrorCode;
import com.sunshine.sandbox.session.SandboxSession;
import com.sunshine.sandbox.session.SandboxSessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SandboxToolExecutorGlobGrepTest {

    @TempDir
    Path tempRoot;

    private SandboxToolExecutor executor;
    private String sessionId;
    private Path hostWorkspace;

    @BeforeEach
    void setUp() throws Exception {
        SandboxSessionStore store = new SandboxSessionStore();
        SandboxProperties props = new SandboxProperties();
        executor = new SandboxToolExecutor(
                store, new StubDockerCli(props), props, null, new SandboxInvocationRegistry());
        sessionId = "sess-glob-001";
        Path hostRoot = tempRoot.resolve(sessionId);
        Path hostSkill = hostRoot.resolve("skills").resolve("demo");
        hostWorkspace = hostRoot.resolve("workspace");
        Files.createDirectories(hostSkill.resolve("scripts"));
        Files.createDirectories(hostWorkspace.resolve("pkg"));
        Files.writeString(hostSkill.resolve("scripts/hello.py"), "print('skill')\n");
        Files.writeString(hostSkill.resolve("scripts/readme.md"), "docs\n");
        Files.writeString(hostWorkspace.resolve("pkg/app.py"), "FINDME line one\nother\nFINDME line three\n");
        Files.writeString(hostWorkspace.resolve("note.txt"), "plain\n");
        store.put(new SandboxSession(
                sessionId,
                "fake-cid",
                hostRoot,
                new SandboxPolicy("docker", "sunshine-sandbox-python:3.11-slim", 30, 256, 0.5,
                        List.of(), List.of(), null),
                null));
    }

    @Test
    void globReturnsOnlyJailContainerPaths() {
        ToolInvokeResponse resp = executor.invoke(sessionId, SandboxToolNames.GLOB, Map.of(
                "pattern", "**/*.py"));
        assertThat(resp.ok()).isTrue();
        List<String> paths = Arrays.stream(resp.output().split("\n"))
                .filter(s -> !s.isBlank())
                .collect(Collectors.toList());
        assertThat(paths).containsExactlyInAnyOrder(
                "/skills/demo/scripts/hello.py",
                "/workspace/pkg/app.py");
        assertThat(paths).allMatch(p -> p.startsWith("/skills/") || p.startsWith("/workspace/"));
        assertThat(paths).noneMatch(p -> p.contains(tempRoot.toString()));
    }

    @Test
    void grepReturnsPathLineExcerpt() {
        ToolInvokeResponse resp = executor.invoke(sessionId, SandboxToolNames.GREP, Map.of(
                "pattern", "FINDME"));
        assertThat(resp.ok()).isTrue();
        List<String> lines = Arrays.stream(resp.output().split("\n"))
                .filter(s -> !s.isBlank())
                .toList();
        assertThat(lines).containsExactly(
                "/workspace/pkg/app.py:1:FINDME line one",
                "/workspace/pkg/app.py:3:FINDME line three");
    }

    @Test
    void grepInvalidRegexThrows400() {
        assertThatThrownBy(() -> executor.invoke(sessionId, SandboxToolNames.GREP, Map.of(
                "pattern", "[invalid")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    BizException be = (BizException) e;
                    assertThat(be.getErrorCode().getCode()).isEqualTo(400);
                    assertThat(be.getErrorCode().getKey())
                            .isEqualTo(SandboxErrorCode.PATTERN_INVALID.getKey());
                });
    }

    @Test
    void grepCapsAt200AndSetsHitLimitWithoutTruncatingLine() throws Exception {
        String longExcerpt = "MATCH-" + "x".repeat(500);
        Path many = hostWorkspace.resolve("many.txt");
        StringBuilder body = new StringBuilder();
        IntStream.rangeClosed(1, 250).forEach(i ->
                body.append(longExcerpt).append(' ').append(i).append('\n'));
        Files.writeString(many, body.toString());

        ToolInvokeResponse resp = executor.invoke(sessionId, SandboxToolNames.GREP, Map.of(
                "pattern", "MATCH-",
                "path", "/workspace/many.txt"));
        assertThat(resp.ok()).isTrue();
        assertThat(resp.meta()).containsEntry("hitLimit", true);
        List<String> lines = Arrays.stream(resp.output().split("\n"))
                .filter(s -> !s.isBlank())
                .toList();
        assertThat(lines).hasSize(200);
        String first = lines.get(0);
        assertThat(first).isEqualTo("/workspace/many.txt:1:" + longExcerpt + " 1");
        assertThat(first).doesNotContain("…");
    }

    static final class StubDockerCli extends DockerCli {
        StubDockerCli(SandboxProperties properties) {
            super(properties, new SandboxInvocationRegistry());
        }

        @Override
        public ExecResult exec(String containerId, String workingDir, List<String> cmd, Duration timeout) {
            return new ExecResult(0, "", "");
        }

        @Override
        public ExecResult exec(
                String containerId, String workingDir, List<String> cmd, Duration timeout, String invocationId) {
            return exec(containerId, workingDir, cmd, timeout);
        }
    }
}
