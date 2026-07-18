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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SandboxToolExecutorTest {

    @TempDir
    Path tempRoot;

    private SandboxSessionStore store;
    private SandboxToolExecutor executor;
    private String sessionId;
    private Path hostWorkspace;
    private Path hostSkill;

    @BeforeEach
    void setUp() throws Exception {
        store = new SandboxSessionStore();
        SandboxProperties props = new SandboxProperties();
        executor = new SandboxToolExecutor(
                store, new StubDockerCli(props), props, null, new SandboxInvocationRegistry());
        sessionId = "sess-tool-001";
        Path hostRoot = tempRoot.resolve(sessionId);
        hostSkill = hostRoot.resolve("skills").resolve("demo");
        hostWorkspace = hostRoot.resolve("workspace");
        Files.createDirectories(hostSkill.resolve("scripts"));
        Files.createDirectories(hostWorkspace);
        Files.writeString(hostSkill.resolve("scripts/hello.py"), "print(1)\n");
        Files.writeString(hostWorkspace.resolve("note.txt"), "aaa\nbbb\naaa\n");
        store.put(new SandboxSession(
                sessionId,
                "fake-cid",
                hostRoot,
                new SandboxPolicy("docker", "sunshine-sandbox-python:3.11-slim", 30, 256, 0.5,
                        List.of(), List.of())));
    }

    @Test
    void editReplacesOnce() {
        ToolInvokeResponse ok = executor.invoke(sessionId, SandboxToolNames.EDIT, Map.of(
                "path", "/workspace/note.txt",
                "old_string", "bbb",
                "new_string", "CCC"));
        assertThat(ok.ok()).isTrue();
        assertThat(hostWorkspace.resolve("note.txt")).hasContent("aaa\nCCC\naaa\n");

        assertThatThrownBy(() -> executor.invoke(sessionId, SandboxToolNames.EDIT, Map.of(
                "path", "/workspace/note.txt",
                "old_string", "aaa",
                "new_string", "XXX")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode().getKey())
                        .isEqualTo(SandboxErrorCode.EDIT_NOT_UNIQUE.getKey()));
    }

    @Test
    void editNotFound() {
        assertThatThrownBy(() -> executor.invoke(sessionId, SandboxToolNames.EDIT, Map.of(
                "path", "/workspace/note.txt",
                "old_string", "zzz",
                "new_string", "YYY")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    BizException be = (BizException) e;
                    assertThat(be.getErrorCode().getKey()).isEqualTo(SandboxErrorCode.EDIT_NOT_FOUND.getKey());
                    assertThat(be.getMessage()).contains("old_string not found");
                });
    }

    @Test
    void writeRejectsExistingFile() {
        assertThatThrownBy(() -> executor.invoke(sessionId, SandboxToolNames.WRITE, Map.of(
                "path", "/workspace/note.txt",
                "content", "overwrite")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    BizException be = (BizException) e;
                    assertThat(be.getErrorCode().getKey()).isEqualTo(SandboxErrorCode.WRITE_ALREADY_EXISTS.getKey());
                    assertThat(be.getMessage()).contains("file already exists")
                            .contains("sandbox__edit");
                });
        assertThat(hostWorkspace.resolve("note.txt")).hasContent("aaa\nbbb\naaa\n");
    }

    @Test
    void readRejectsDirectory() {
        assertThatThrownBy(() -> executor.invoke(sessionId, SandboxToolNames.READ, Map.of(
                "path", "/workspace")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode().getKey())
                        .isEqualTo(SandboxErrorCode.NOT_A_FILE.getKey()));
    }

    @Test
    void execBlocksDangerousCommand() {
        assertThatThrownBy(() -> executor.invoke(sessionId, SandboxToolNames.EXEC, Map.of(
                "command", "rm -rf /")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    BizException be = (BizException) e;
                    assertThat(be.getErrorCode().getKey()).isEqualTo(SandboxErrorCode.EXEC_BLOCKED.getKey());
                    assertThat(be.getMessage()).contains("command blocked");
                });
    }

    @Test
    void readAndWriteHappyPath() {
        ToolInvokeResponse written = executor.invoke(sessionId, SandboxToolNames.WRITE, Map.of(
                "path", "/workspace/out/a.txt",
                "content", "hello\nworld\n"));
        assertThat(written.ok()).isTrue();
        assertThat(hostWorkspace.resolve("out/a.txt")).hasContent("hello\nworld\n");

        ToolInvokeResponse read = executor.invoke(sessionId, SandboxToolNames.READ, Map.of(
                "path", "/workspace/out/a.txt"));
        assertThat(read.ok()).isTrue();
        assertThat(read.output()).isEqualTo("hello\nworld\n");

        ToolInvokeResponse skillRead = executor.invoke(sessionId, SandboxToolNames.READ, Map.of(
                "path", "/skills/demo/scripts/hello.py"));
        assertThat(skillRead.ok()).isTrue();
        assertThat(skillRead.output()).isEqualTo("print(1)\n");
    }

    @Test
    void readSupportsOffsetLimitAndTruncation() {
        ToolInvokeResponse lines = executor.invoke(sessionId, SandboxToolNames.READ, Map.of(
                "path", "/workspace/note.txt",
                "offset", 2,
                "limit", 1));
        assertThat(lines.ok()).isTrue();
        assertThat(lines.output()).isEqualTo("bbb\n");

        StringBuilder big = new StringBuilder();
        while (big.length() < 210_000) {
            big.append("x");
        }
        executor.invoke(sessionId, SandboxToolNames.WRITE, Map.of(
                "path", "/workspace/big.txt",
                "content", big.toString()));
        ToolInvokeResponse truncated = executor.invoke(sessionId, SandboxToolNames.READ, Map.of(
                "path", "/workspace/big.txt"));
        assertThat(truncated.ok()).isTrue();
        assertThat(truncated.output()).hasSize(200_000);
        assertThat(truncated.meta()).containsEntry("truncated", true);
    }

    @Test
    void writeRejectedOutsideWorkspace() {
        assertThatThrownBy(() -> executor.invoke(sessionId, SandboxToolNames.WRITE, Map.of(
                "path", "/skills/demo/scripts/x.py",
                "content", "nope")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().getKey())
                .isEqualTo(SandboxErrorCode.FILE_PATH_INVALID.getKey());
    }

    @Test
    void unknownSessionAndTools() {
        assertThatThrownBy(() -> executor.invoke("missing", SandboxToolNames.READ, Map.of("path", "/workspace/a")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(SandboxErrorCode.SESSION_NOT_FOUND);

        assertThatThrownBy(() -> executor.invoke(sessionId, "nope", Map.of()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(SandboxErrorCode.TOOL_UNKNOWN);

        assertThatThrownBy(() -> executor.invoke(sessionId, SandboxToolNames.EXEC, Map.of()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().getKey())
                .isEqualTo(SandboxErrorCode.FILE_PATH_INVALID.getKey());
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
