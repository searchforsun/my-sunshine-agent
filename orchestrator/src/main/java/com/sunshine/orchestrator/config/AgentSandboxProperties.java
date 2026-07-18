package com.sunshine.orchestrator.config;

import com.sunshine.orchestrator.sandbox.SandboxIds;
import com.sunshine.orchestrator.sandbox.SandboxToolDefaults;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** 六工具 displayName / description / JSON Schema — SSOT：Nacos agent.sandbox.tools */
    private Map<String, ToolDef> tools = new LinkedHashMap<>(SandboxToolDefaults.all());

    /** 可单工具取消的名单 — SSOT：Nacos agent.sandbox.cancellable-tools */
    private List<String> cancellableTools = new ArrayList<>(List.of(
            SandboxIds.EXEC, SandboxIds.GREP, SandboxIds.GLOB));

    /** 用户取消后同族最多再执行次数 */
    private int cancelMaxFollowups = 3;

    /**
     * 取消时回主 Agent 的 tool result；占位符 {params} {remaining}
     */
    private String cancelResult = """
            用户已取消该沙箱工具调用。请换方案继续（勿重复同一命令）。原参数：{params}。本轮同族还可再调用 {remaining} 次。""";

    /** 预算耗尽拒调文案 */
    private String budgetExhausted = "本轮用户取消后同族沙箱工具调用次数已用尽，请直接作答或改用其它能力。";

    /** 时间线工具步取消 after */
    private String cancelAfter = "已取消";

    /** 时间线步骤中文名；未配置时回退 toolId */
    public String displayName(String toolId) {
        ToolDef def = resolveTool(toolId);
        if (def == null || def.displayName == null || def.displayName.isBlank()) {
            return toolId;
        }
        return def.displayName;
    }

    public ToolDef resolveTool(String toolId) {
        if (toolId == null || toolId.isBlank()) {
            return null;
        }
        Map<String, ToolDef> effective = tools == null || tools.isEmpty()
                ? SandboxToolDefaults.all() : tools;
        return effective.get(toolId);
    }

    public boolean isSandboxTool(String toolId) {
        return toolId != null && SandboxIds.ALL.contains(toolId);
    }

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

    @Getter
    @Setter
    public static class ToolDef {
        private String displayName;
        private String description;
        private Map<String, ParamDef> properties = new LinkedHashMap<>();
        private List<String> required = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class ParamDef {
        private String type = "string";
        private String description;
    }
}
