package com.sunshine.sandbox.docker;

import com.sunshine.sandbox.config.SandboxProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class DockerCli {

    private final SandboxProperties properties;
    private final SandboxInvocationRegistry invocationRegistry;

    /** args 为 docker 子命令及参数（如 run / exec / rm），返回 stdout trim */
    public String runDetached(List<String> args) {
        String out = run(args, Duration.ofMinutes(2));
        return out == null ? "" : out.trim();
    }

    /**
     * {@code docker exec -w {workingDir} {containerId} ...cmd}；超时 destroyForcibly，
     * 返回 {@code exitCode=-1} 且 stdout 含 {@code timeout}（供工具面软失败）。
     */
    public ExecResult exec(String containerId, String workingDir, List<String> cmd, Duration timeout) {
        return exec(containerId, workingDir, cmd, timeout, null);
    }

    public ExecResult exec(
            String containerId, String workingDir, List<String> cmd, Duration timeout, String invocationId) {
        List<String> args = new ArrayList<>();
        args.add("exec");
        args.add("-w");
        args.add(workingDir);
        args.add(containerId);
        args.addAll(cmd);
        try {
            return runCapture(args, timeout, invocationId);
        } catch (IllegalStateException e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("timed out")) {
                return new ExecResult(-1, "timeout", "");
            }
            if (msg != null && msg.contains("cancelled")) {
                return new ExecResult(-1, "cancelled", "");
            }
            throw e;
        }
    }

    public void removeForce(String containerIdOrName) {
        run(List.of("rm", "-f", containerIdOrName), Duration.ofMinutes(1));
    }

    public void stop(String containerIdOrName) {
        run(List.of("stop", containerIdOrName), Duration.ofMinutes(2));
    }

    public void start(String containerIdOrName) {
        if (isRunning(containerIdOrName)) {
            return;
        }
        run(List.of("start", containerIdOrName), Duration.ofMinutes(2));
    }

    public boolean isRunning(String containerIdOrName) {
        ExecResult r = runCapture(
                List.of("inspect", "-f", "{{.State.Running}}", containerIdOrName),
                Duration.ofSeconds(30));
        return "true".equalsIgnoreCase(r.stdout().trim());
    }

    /** 网络已存在则跳过；否则 {@code docker network create}。 */
    public void ensureNetwork(String networkName) {
        ExecResult inspect = runCapture(List.of("network", "inspect", networkName), Duration.ofSeconds(30));
        if (inspect.exitCode() == 0) {
            return;
        }
        run(List.of("network", "create", networkName), Duration.ofSeconds(30));
    }

    /**
     * 容器已在跑则复用并返回 name；否则 {@code rm -f} 后按 args 启动。
     * args 须为完整 {@code run -d --name ...} 子命令列表。
     */
    public String runOrReuse(String name, List<String> runArgs) {
        if (isRunning(name)) {
            return name;
        }
        try {
            removeForce(name);
        } catch (RuntimeException e) {
            log.debug("runOrReuse remove before start {}: {}", name, e.getMessage());
        }
        return runDetached(runArgs);
    }

    private String run(List<String> args, Duration timeout) {
        ExecResult r = runCapture(args, timeout, null);
        if (r.exitCode() != 0) {
            throw new IllegalStateException(
                    "docker " + args.get(0) + " failed exit=" + r.exitCode() + " stderr=" + r.stderr());
        }
        return r.stdout();
    }

    private ExecResult runCapture(List<String> args, Duration timeout) {
        return runCapture(args, timeout, null);
    }

    private ExecResult runCapture(List<String> args, Duration timeout, String invocationId) {
        List<String> full = new ArrayList<>();
        full.add(properties.getDocker().getBinary());
        full.addAll(args);
        log.debug("docker cli: {}", full);
        ProcessBuilder pb = new ProcessBuilder(full);
        pb.redirectErrorStream(false);
        String invId = invocationId != null && !invocationId.isBlank() ? invocationId.strip() : null;
        try {
            Process p = pb.start();
            if (invId != null) {
                invocationRegistry.bindProcess(invId, p);
            }
            ByteArrayOutputStream stdoutBuf = new ByteArrayOutputStream();
            ByteArrayOutputStream stderrBuf = new ByteArrayOutputStream();
            Thread tOut = drain(p.getInputStream(), stdoutBuf);
            Thread tErr = drain(p.getErrorStream(), stderrBuf);
            boolean finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                tOut.join(1000);
                tErr.join(1000);
                throw new IllegalStateException("docker timed out: " + full);
            }
            tOut.join(1000);
            tErr.join(1000);
            if (invId != null && invocationRegistry.isCancelled(invId)) {
                throw new IllegalStateException("docker cancelled: " + full);
            }
            return new ExecResult(
                    p.exitValue(),
                    stdoutBuf.toString(StandardCharsets.UTF_8),
                    stderrBuf.toString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("docker invoke failed: " + full, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("docker invoke interrupted: " + full, e);
        } finally {
            if (invId != null) {
                invocationRegistry.unbind(invId);
            }
        }
    }

    private static Thread drain(InputStream in, ByteArrayOutputStream out) {
        Thread t = new Thread(() -> {
            try {
                in.transferTo(out);
            } catch (IOException ignored) {
                // process ended
            }
        }, "docker-cli-drain");
        t.setDaemon(true);
        t.start();
        return t;
    }
}
