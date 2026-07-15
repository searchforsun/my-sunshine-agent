package com.sunshine.sandbox.tool;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.sandbox.api.SandboxPolicyDto;
import com.sunshine.sandbox.api.ToolInvokeResponse;
import com.sunshine.sandbox.config.SandboxProperties;
import com.sunshine.sandbox.docker.DockerCli;
import com.sunshine.sandbox.docker.ExecResult;
import com.sunshine.sandbox.exception.SandboxErrorCode;
import com.sunshine.sandbox.session.SandboxSession;
import com.sunshine.sandbox.session.SandboxSessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SandboxExecTest {

    @TempDir
    Path tempRoot;

    private SandboxSessionStore store;
    private FakeDockerCli docker;
    private SandboxProperties props;
    private SandboxToolExecutor executor;
    private String sessionId;
    private Path hostWorkspace;

    @BeforeEach
    void setUp() throws Exception {
        store = new SandboxSessionStore();
        props = new SandboxProperties();
        props.getDocker().setDefaultTimeoutSec(30);
        docker = new FakeDockerCli(props);
        executor = new SandboxToolExecutor(store, docker, props);
        sessionId = "sess-exec-001";
        Path hostRoot = tempRoot.resolve(sessionId);
        Path hostSkill = hostRoot.resolve("skill");
        hostWorkspace = hostRoot.resolve("workspace");
        Files.createDirectories(hostSkill);
        Files.createDirectories(hostWorkspace);
        store.put(new SandboxSession(
                sessionId,
                "cid-exec-1",
                hostRoot,
                new SandboxPolicyDto("docker", "sunshine-sandbox-python:3.11-slim", 45, 256, 0.5,
                        List.of(), List.of())));
    }

    @Test
    void execWiresCommandCwdAndPolicyTimeout() {
        docker.nextResult = new ExecResult(0, "ok\n", "");
        ToolInvokeResponse resp = executor.invoke(sessionId, SandboxToolNames.EXEC, Map.of(
                "command", "echo ok",
                "cwd", "/workspace"));
        assertThat(resp.ok()).isTrue();
        assertThat(resp.exitCode()).isEqualTo(0);
        assertThat(resp.output()).isEqualTo("ok\n");
        assertThat(docker.invocations).hasSize(1);
        FakeDockerCli.Invocation inv = docker.invocations.get(0);
        assertThat(inv.containerId()).isEqualTo("cid-exec-1");
        assertThat(inv.workingDir()).isEqualTo("/workspace");
        assertThat(inv.cmd()).containsExactly("sh", "-lc", "echo ok");
        assertThat(inv.timeout()).isEqualTo(Duration.ofSeconds(45));
    }

    @Test
    void execDefaultsCwdToWorkspaceAndHonorsTimeoutOverride() {
        docker.nextResult = new ExecResult(0, "pwd\n", "");
        ToolInvokeResponse resp = executor.invoke(sessionId, SandboxToolNames.EXEC, Map.of(
                "command", "pwd",
                "timeout_sec", 12));
        assertThat(resp.ok()).isTrue();
        FakeDockerCli.Invocation inv = docker.invocations.get(0);
        assertThat(inv.workingDir()).isEqualTo("/workspace");
        assertThat(inv.timeout()).isEqualTo(Duration.ofSeconds(12));
    }

    @Test
    void execUsesPropertiesDefaultWhenPolicyTimeoutMissing() {
        String sid = "sess-exec-default-to";
        Path hostRoot = tempRoot.resolve(sid);
        try {
            Files.createDirectories(hostRoot.resolve("workspace"));
            Files.createDirectories(hostRoot.resolve("skill"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        store.put(new SandboxSession(
                sid,
                "cid-2",
                hostRoot,
                new SandboxPolicyDto("docker", "img", null, 256, 0.5, List.of(), List.of())));
        docker.nextResult = new ExecResult(0, "", "");
        executor.invoke(sid, SandboxToolNames.EXEC, Map.of("command", "true"));
        assertThat(docker.invocations.get(0).timeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void execTimeoutReturnsSoftFailure() {
        docker.nextResult = new ExecResult(-1, "timeout", "");
        ToolInvokeResponse resp = executor.invoke(sessionId, SandboxToolNames.EXEC, Map.of(
                "command", "sleep 99"));
        assertThat(resp.ok()).isFalse();
        assertThat(resp.exitCode()).isEqualTo(-1);
        assertThat(resp.output()).contains("timeout");
    }

    @Test
    void execNonZeroExitIsNotOk() {
        docker.nextResult = new ExecResult(7, "out", "err");
        ToolInvokeResponse resp = executor.invoke(sessionId, SandboxToolNames.EXEC, Map.of(
                "command", "false"));
        assertThat(resp.ok()).isFalse();
        assertThat(resp.exitCode()).isEqualTo(7);
        assertThat(resp.output()).isEqualTo("outerr");
    }

    @Test
    void execRejectsCwdOutsideJail() {
        assertThatThrownBy(() -> executor.invoke(sessionId, SandboxToolNames.EXEC, Map.of(
                "command", "ls",
                "cwd", "/tmp")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().getKey())
                .isEqualTo(SandboxErrorCode.FILE_PATH_INVALID.getKey());
        assertThat(docker.invocations).isEmpty();
    }

    /**
     * 同 session 的 write 与 exec 共用 host bind mount（/workspace）；
     * 此处写文件证明 workspace 可见，真实 docker exec 会读到同一路径。
     */
    @Test
    void sameSessionWriteSharesHostFsWithExecWorkspace() {
        executor.invoke(sessionId, SandboxToolNames.WRITE, Map.of(
                "path", "/workspace/shared.txt",
                "content", "from-write\n"));
        assertThat(hostWorkspace.resolve("shared.txt")).hasContent("from-write\n");
        // exec 在容器内 cat /workspace/shared.txt 将看到同一 bind mount 内容（Fake 仅校验 cwd/命令）
        docker.nextResult = new ExecResult(0, "from-write\n", "");
        ToolInvokeResponse resp = executor.invoke(sessionId, SandboxToolNames.EXEC, Map.of(
                "command", "cat /workspace/shared.txt"));
        assertThat(resp.ok()).isTrue();
        assertThat(resp.output()).isEqualTo("from-write\n");
        assertThat(docker.invocations.get(0).workingDir()).isEqualTo("/workspace");
    }

    static final class FakeDockerCli extends DockerCli {
        final List<Invocation> invocations = new ArrayList<>();
        ExecResult nextResult = new ExecResult(0, "", "");

        FakeDockerCli(SandboxProperties properties) {
            super(properties);
        }

        @Override
        public ExecResult exec(String containerId, String workingDir, List<String> cmd, Duration timeout) {
            invocations.add(new Invocation(containerId, workingDir, List.copyOf(cmd), timeout));
            return nextResult;
        }

        record Invocation(String containerId, String workingDir, List<String> cmd, Duration timeout) {}
    }
}
