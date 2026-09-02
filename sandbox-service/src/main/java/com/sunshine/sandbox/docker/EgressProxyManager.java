package com.sunshine.sandbox.docker;

import com.sunshine.sandbox.config.SandboxProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * egress 代理（tinyproxy + Filter ACL），支持共享容器（向后兼容）和 per-session 容器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EgressProxyManager {

    public static final String NETWORK_NAME = "sunshine-sandbox-net";
    public static final String CONTAINER_NAME = "sunshine-sandbox-egress";

    private final DockerCli dockerCli;
    private final SandboxProperties properties;

    /** 共享容器最近一次成功 apply 的 ALLOW 串 */
    private volatile String lastAllowCsv;

    /** per-session 容器 Map：sessionId → containerName */
    private final ConcurrentHashMap<String, String> sessionContainers = new ConcurrentHashMap<>();
    /** per-session 容器上一次 ALLOW 串 */
    private final ConcurrentHashMap<String, String> sessionAllowCsv = new ConcurrentHashMap<>();

    /**
     * Per-session egress 容器（工作区级隔离）。
     */
    public synchronized void ensureRunning(String sessionId, List<String> allowHosts) {
        if (!StringUtils.hasText(sessionId)) {
            ensureRunning(allowHosts);
            return;
        }
        String containerName = "sunshine-sandbox-egress-" + sessionId.substring(0, Math.min(sessionId.length(), 12));
        String allowCsv = normalizeAllow(allowHosts);
        String prevAllow = sessionAllowCsv.get(sessionId);
        if (allowCsv.equals(prevAllow) && dockerCli.isRunning(containerName)) {
            log.debug("egress reuse session={} name={} allow={}", sessionId, containerName, allowCsv);
            return;
        }
        try {
            dockerCli.removeForce(containerName);
        } catch (RuntimeException e) {
            log.debug("egress remove before recreate session={}: {}", sessionId, e.getMessage());
        }
        dockerCli.ensureNetwork(NETWORK_NAME);
        List<String> args = new ArrayList<>();
        args.add("run");
        args.add("-d");
        args.add("--name");
        args.add(containerName);
        args.add("--network");
        args.add(NETWORK_NAME);
        args.add("-e");
        args.add("ALLOW=" + allowCsv);
        args.add(properties.getEgress().getProxyImage());
        dockerCli.runDetached(args);
        sessionContainers.put(sessionId, containerName);
        sessionAllowCsv.put(sessionId, allowCsv);
        log.info("egress started session={} name={} allow={}", sessionId, containerName, allowCsv);
    }

    /** Per-session 代理地址 */
    public String proxyUrl(String sessionId) {
        if (sessionId == null) return proxyUrl();
        String name = sessionContainers.get(sessionId);
        return "http://" + (name != null ? name : CONTAINER_NAME) + ":" + properties.getEgress().getProxyPort();
    }

    /** 清理 per-session egress 容器 */
    public void removeEgress(String sessionId) {
        String name = sessionContainers.remove(sessionId);
        sessionAllowCsv.remove(sessionId);
        if (name != null) {
            try {
                dockerCli.removeForce(name);
            } catch (RuntimeException e) {
                log.warn("egress remove clean failed session={} name={}: {}", sessionId, name, e.getMessage());
            }
        }
    }

    /**
     * 共享 egress 代理（向后兼容，无 sessionId 的调用）。
     */
    public synchronized void ensureRunning(List<String> allowHosts) {
        String allowCsv = normalizeAllow(allowHosts);
        dockerCli.ensureNetwork(NETWORK_NAME);
        if (allowCsv.equals(lastAllowCsv) && dockerCli.isRunning(CONTAINER_NAME)) {
            log.debug("egress reuse name={} allow={}", CONTAINER_NAME, allowCsv);
            return;
        }
        try {
            dockerCli.removeForce(CONTAINER_NAME);
        } catch (RuntimeException e) {
            log.debug("egress remove before recreate: {}", e.getMessage());
        }
        int port = properties.getEgress().getProxyPort();
        String image = properties.getEgress().getProxyImage();
        List<String> args = new ArrayList<>();
        args.add("run");
        args.add("-d");
        args.add("--name");
        args.add(CONTAINER_NAME);
        args.add("--network");
        args.add(NETWORK_NAME);
        args.add("-e");
        args.add("ALLOW=" + allowCsv);
        args.add(image);
        dockerCli.runDetached(args);
        lastAllowCsv = allowCsv;
        log.info("egress started name={} port={} allow={}", CONTAINER_NAME, port, allowCsv);
    }

    public String proxyUrl() {
        return "http://" + CONTAINER_NAME + ":" + properties.getEgress().getProxyPort();
    }

    static String normalizeAllow(List<String> allowHosts) {
        if (allowHosts == null || allowHosts.isEmpty()) {
            return "";
        }
        return allowHosts.stream()
                .filter(h -> h != null && !h.isBlank())
                .map(String::trim)
                .distinct()
                .collect(Collectors.joining(","));
    }
}
