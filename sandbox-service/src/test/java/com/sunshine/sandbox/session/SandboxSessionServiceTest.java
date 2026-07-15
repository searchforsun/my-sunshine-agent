package com.sunshine.sandbox.session;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.sandbox.api.CreateSessionRequest;
import com.sunshine.sandbox.api.SandboxPolicyDto;
import com.sunshine.sandbox.config.SandboxProperties;
import com.sunshine.sandbox.docker.DockerCli;
import com.sunshine.sandbox.docker.EgressProxyManager;
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
        SandboxProperties props = newProps();
        docker = new FakeDockerCli(props);
        store = new SandboxSessionStore();
        service = new SandboxSessionService(docker, store, props, new EgressProxyManager(docker, props));
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
        assertThat(docker.lastRunArgs).noneMatch(a -> a.startsWith("HTTP_PROXY="));
        assertThat(docker.lastRunArgs).anyMatch(a -> a.endsWith(":/skill:ro"));
        assertThat(docker.lastRunArgs).anyMatch(a -> a.endsWith(":/workspace"));
        assertThat(store.get(sessionId)).isPresent();

        service.close(sessionId);

        assertThat(docker.removed).contains(docker.lastSandboxContainerId);
        assertThat(store.get(sessionId)).isEmpty();
        assertThat(tempRoot.resolve(sessionId)).doesNotExist();
    }

    @Test
    void createWithNetworkAllowUsesSandboxNetAndProxyEnv() {
        CreateSessionRequest req = new CreateSessionRequest(
                "u1", "t1", "demo", "r1",
                new SandboxPolicyDto(null, null, null, null, null,
                        List.of("pypi.org", "files.pythonhosted.org"), null),
                Map.of(), Map.of());

        String sessionId = service.create(req);

        assertThat(sessionId).isNotBlank();
        assertThat(docker.ensuredNetworks).contains(EgressProxyManager.NETWORK_NAME);
        assertThat(docker.lastRunArgs).contains(EgressProxyManager.NETWORK_NAME);
        assertThat(docker.lastRunArgs).contains(
                "HTTP_PROXY=http://" + EgressProxyManager.CONTAINER_NAME + ":8888");
        assertThat(docker.lastRunArgs).contains(
                "HTTPS_PROXY=http://" + EgressProxyManager.CONTAINER_NAME + ":8888");
        assertThat(docker.lastRunArgs).contains("NO_PROXY=localhost,127.0.0.1");
        assertThat(docker.lastRunArgs).doesNotContain("none");
        assertThat(docker.runInvocations).anySatisfy(args ->
                assertThat(args).contains(EgressProxyManager.CONTAINER_NAME)
                        .anyMatch(a -> a.startsWith("ALLOW=")));
    }

    @Test
    void emptyNetworkAllowStillUsesNone() {
        CreateSessionRequest req = new CreateSessionRequest(
                "u1", "t1", "demo", "r1",
                new SandboxPolicyDto(null, null, null, null, null, List.of(), null),
                Map.of(), Map.of());
        service.create(req);
        assertThat(docker.lastRunArgs).containsSequence("--network", "none");
        assertThat(docker.lastRunArgs).noneMatch(a -> a.startsWith("HTTP_PROXY="));
        assertThat(docker.ensuredNetworks).isEmpty();
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
        SandboxProperties props = newProps();
        props.getDocker().setDefaultImage("");
        service = new SandboxSessionService(docker, store, props, new EgressProxyManager(docker, props));
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
        SandboxProperties props = newProps();
        service = new SandboxSessionService(docker, failingStore, props, new EgressProxyManager(docker, props));
        CreateSessionRequest req = new CreateSessionRequest(
                "u1", "t1", "demo", "r1",
                new SandboxPolicyDto("docker", "sunshine-sandbox-python:3.11-slim", 30, 256, 0.5,
                        List.of(), List.of()),
                Map.of(), Map.of());
        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("store down");
        assertThat(docker.removed).contains(docker.lastSandboxContainerId);
    }

    private SandboxProperties newProps() {
        SandboxProperties props = new SandboxProperties();
        props.getDocker().setHostDataRoot(tempRoot.toString());
        return props;
    }

    /** Fake：记录 run/remove/network，不调真实 Docker */
    static final class FakeDockerCli extends DockerCli {
        List<String> lastRunArgs;
        String lastSandboxContainerId = "fake-cid-001";
        final List<String> removed = new ArrayList<>();
        final List<String> ensuredNetworks = new ArrayList<>();
        final List<List<String>> runInvocations = new ArrayList<>();
        final List<String> running = new ArrayList<>();

        FakeDockerCli(SandboxProperties properties) {
            super(properties);
        }

        @Override
        public String runDetached(List<String> args) {
            runInvocations.add(List.copyOf(args));
            lastRunArgs = List.copyOf(args);
            int nameIdx = args.indexOf("--name");
            if (nameIdx >= 0 && nameIdx + 1 < args.size()) {
                String name = args.get(nameIdx + 1);
                running.add(name);
                if (EgressProxyManager.CONTAINER_NAME.equals(name)) {
                    return "egress-cid";
                }
            }
            return lastSandboxContainerId;
        }

        @Override
        public ExecResult exec(String containerId, String workingDir, List<String> cmd, Duration timeout) {
            return new ExecResult(0, "", "");
        }

        @Override
        public void removeForce(String containerIdOrName) {
            removed.add(containerIdOrName);
            running.remove(containerIdOrName);
        }

        @Override
        public boolean isRunning(String containerIdOrName) {
            return running.contains(containerIdOrName);
        }

        @Override
        public void ensureNetwork(String networkName) {
            ensuredNetworks.add(networkName);
        }
    }
}
