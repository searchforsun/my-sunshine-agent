package com.sunshine.orchestrator.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 对话级沙箱会话 — SSOT：Nacos agent.sandbox */
@Getter
@Setter
@RefreshScope
@Component
@ConfigurationProperties(prefix = "agent.sandbox")
public class AgentSandboxProperties {

    /** 对话级 workspace 空闲 TTL（秒），默认 30min；到期停机（docker stop），活动时续期 */
    private int conversationTtlSec = 1800;

    /** 自上次活动起销毁 TTL（秒），默认 7 天；到期 docker rm + 清盘 */
    private int purgeTtlSec = 604_800;

    /** 读文件内容最大字符数（超出截断） */
    private int workspaceContentMaxChars = 200_000;

    private long reaperIntervalMs = 60_000;

    /** 统一基座运行时（不绑 Skill） */
    private Runtime runtime = new Runtime();

    @Getter
    @Setter
    public static class Runtime {
        private String image = "sunshine-sandbox-python:3.11-slim";
        private String runtimeType = "docker";
        private int timeoutSec = 30;
        private int memoryMb = 256;
        private double cpus = 0.5;
        private List<String> networkAllow = new ArrayList<>();
        private List<String> execReadonlyAllow = new ArrayList<>(List.of(
                "ls *", "pwd", "python -m pytest *"));
    }
}
