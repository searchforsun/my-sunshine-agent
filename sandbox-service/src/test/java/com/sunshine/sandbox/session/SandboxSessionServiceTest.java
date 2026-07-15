package com.sunshine.sandbox.session;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.sandbox.api.CreateSessionRequest;
import com.sunshine.sandbox.api.SandboxPolicyDto;
import com.sunshine.sandbox.config.SandboxProperties;
import com.sunshine.sandbox.docker.DockerCli;
import com.sunshine.sandbox.docker.ExecResult;
import com.sunshine.sandbox.exception.SandboxErrorCode;
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

class SandboxSessionServiceTest {

    @TempDir
    Path tempRoot;

    private FakeDockerCli docker;
    private SandboxSessionStore store;
    private SandboxSessionService service;

    @BeforeEach
    void setUp() {
        SandboxProperties props = new SandboxProperties();
        props.getDocker().setHostDataRoot(tempRoot.toString());
        docker = new FakeDockerCli(props);
        store = new SandboxSessionStore();
        service = new SandboxSessionService(docker, store, props);
    }

    @Test
    void createWritesFilesAndCloseRemovesContainer() throws Exception {
        CreateSessionRequest req = new CreateSessionRequest(
                "u1", "t1", "demo", "r1",
                new SandboxPolicyDto("docker", "sunshine-sandbox-python:3.11-slim", 30, 256, 0.5,
                        List.of(), List.of("ls *")),
                Map.of("scripts/hello.py", "print(1)"),
                Map.of("note.txt", "hi"));

        String sessionId = service.create(req);

        assertThat(sessionId).isNotBlank();
        Path skillFile = tempRoot.resolve(sessionId).resolve("skill").resolve("scripts/hello.py");
        Path wsFile = tempRoot.resolve(sessionId).resolve("workspace").resolve("note.txt");
        assertThat(skillFile).exists();
        assertThat(Files.readString(skillFile)).isEqualTo("print(1)");
        assertThat(wsFile).exists();
        assertThat(Files.readString(wsFile)).isEqualTo("hi");

        assertThat(docker.lastRunArgs).isNotNull();
        assertThat(docker.lastRunArgs).contains(
                "run", "-d", "--network", "none", "--read-only",
                "--user", "10001:10001", "--cap-drop", "ALL",
                "sunshine-sandbox-python:3.11-slim", "sleep", "infinity");
        assertThat(docker.lastRunArgs).anyMatch(a -> a.endsWith(":/skill:ro"));
        assertThat(docker.lastRunArgs).anyMatch(a -> a.endsWith(":/workspace"));
        assertThat(store.get(sessionId)).isPresent();

        service.close(sessionId);

        assertThat(docker.removed).containsExactly(docker.lastContainerId);
        assertThat(store.get(sessionId)).isEmpty();
        assertThat(tempRoot.resolve(sessionId)).doesNotExist();
    }

    @Test
    void rejectsNonEmptyNetworkAllow() {
        CreateSessionRequest req = new CreateSessionRequest(
                "u1", "t1", "demo", "r1",
                new SandboxPolicyDto(null, null, null, null, null, List.of("pypi.org"), null),
                Map.of(), Map.of());
        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(SandboxErrorCode.NETWORK_ALLOW_NOT_SUPPORTED);
        assertThat(docker.lastRunArgs).isNull();
    }

    @Test
    void closeMissingSessionThrows() {
        assertThatThrownBy(() -> service.close("missing"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(SandboxErrorCode.SESSION_NOT_FOUND);
    }

    @Test
    void rejectsSkillFileOutsideScriptsOrReferences() {
        CreateSessionRequest req = new CreateSessionRequest(
                "u1", "t1", "demo", "r1",
                new SandboxPolicyDto(null, null, null, null, null, List.of(), null),
                Map.of("SKILL.md", "x"),
                Map.of());
        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(SandboxErrorCode.SKILL_FILE_PATH_INVALID);
    }

    @Test
    void rejectsInvalidImage() {
        CreateSessionRequest req = new CreateSessionRequest(
                "u1", "t1", "demo", "r1",
                new SandboxPolicyDto(null, "-evil", null, null, null, List.of(), null),
                Map.of(), Map.of());
        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(SandboxErrorCode.IMAGE_INVALID);
        assertThat(docker.lastRunArgs).isNull();
    }

    @Test
    void rejectsBlankImageWhenNoDefault() {
        SandboxProperties props = new SandboxProperties();
        props.getDocker().setHostDataRoot(tempRoot.toString());
        props.getDocker().setDefaultImage("");
        service = new SandboxSessionService(docker, store, props);
        CreateSessionRequest req = new CreateSessionRequest(
                "u1", "t1", "demo", "r1",
                new SandboxPolicyDto(null, "  ", null, null, null, List.of(), null),
                Map.of(), Map.of());
        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(SandboxErrorCode.IMAGE_INVALID);
    }

    @Test
    void removesContainerWhenStoreFailsAfterDockerRun() {
        SandboxSessionStore failingStore = new SandboxSessionStore() {
            @Override
            public void put(SandboxSession session) {
                throw new IllegalStateException("store down");
            }
        };
        service = new SandboxSessionService(docker, failingStore, newProps());
        CreateSessionRequest req = new CreateSessionRequest(
                "u1", "t1", "demo", "r1",
                new SandboxPolicyDto("docker", "sunshine-sandbox-python:3.11-slim", 30, 256, 0.5,
                        List.of(), List.of()),
                Map.of(), Map.of());
        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("store down");
        assertThat(docker.removed).containsExactly(docker.lastContainerId);
    }

    private SandboxProperties newProps() {
        SandboxProperties props = new SandboxProperties();
        props.getDocker().setHostDataRoot(tempRoot.toString());
        return props;
    }

    /** Fake：记录 run/remove，不调真实 Docker */
    static final class FakeDockerCli extends DockerCli {
        List<String> lastRunArgs;
        String lastContainerId = "fake-cid-001";
        final List<String> removed = new ArrayList<>();

        FakeDockerCli(SandboxProperties properties) {
            super(properties);
        }

        @Override
        public String runDetached(List<String> args) {
            lastRunArgs = List.copyOf(args);
            return lastContainerId;
        }

        @Override
        public ExecResult exec(String containerId, List<String> cmd, Duration timeout) {
            return new ExecResult(0, "", "");
        }

        @Override
        public void removeForce(String containerIdOrName) {
            removed.add(containerIdOrName);
        }

        @Override
        public boolean isRunning(String containerIdOrName) {
            return !removed.contains(containerIdOrName);
        }
    }
}
