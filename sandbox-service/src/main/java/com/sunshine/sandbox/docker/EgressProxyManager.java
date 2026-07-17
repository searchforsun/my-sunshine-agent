package com.sunshine.sandbox.docker;

import com.sunshine.sandbox.config.SandboxProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 共享 egress 代理（tinyproxy + Filter ACL）。
 * <p>v1：全实例一个 {@link #CONTAINER_NAME}；每次 ensure 用<strong>当前会话</strong>的
 * {@code network_allow} 重写 ALLOW（列表变化则重启容器）。并发会话白名单不同时会互相覆盖，
 * 可接受；后续若需隔离再改为 per-session egress。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EgressProxyManager {

    public static final String NETWORK_NAME = "sunshine-sandbox-net";
    public static final String CONTAINER_NAME = "sunshine-sandbox-egress";

    private final DockerCli dockerCli;
    private final SandboxProperties properties;

    /** 最近一次成功 apply 的 ALLOW 串；同列表且容器在跑则跳过重启 */
    private volatile String lastAllowCsv;

    /**
     * 确保 bridge 网络存在，并按 allowHosts 启动/复用 egress 容器。
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
