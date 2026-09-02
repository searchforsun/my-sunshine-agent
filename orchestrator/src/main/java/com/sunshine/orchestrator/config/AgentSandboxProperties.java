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
import java.math.BigDecimal;

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

    /** 读文件内容最大字符数（超出截断）；前端虚拟滚动按行渲染，单次 50k 字符约 2k 行可流畅浏览 */
    private int workspaceContentMaxChars = 50_000;

    private long reaperIntervalMs = 60_000;

    /** 统一基座运行时（不绑 Skill） */
    private Runtime runtime = new Runtime();

    /** 六工具 displayName / description / JSON Schema — SSOT：Nacos agent.sandbox.tools */
    private Map<String, ToolDef> tools = new LinkedHashMap<>(SandboxToolDefaults.all());

    /** 可单工具取消的名单 — SSOT：Nacos agent.sandbox.cancellable-tools */
    private List<String> cancellableTools = new ArrayList<>(List.of(
            SandboxIds.EXEC, SandboxIds.GREP, SandboxIds.GLOB));

    /** 时间线工具步取消 after */
    private String cancelAfter = "已取消";

    /** 硬件档位预设 */
    private Map<String, ProfilePreset> profiles = new LinkedHashMap<>();

    public ProfilePreset resolveProfile(String name) {
        if (profiles == null || profiles.isEmpty()) {
            ProfilePreset fallback = new ProfilePreset();
            fallback.setImage("sunshine-sandbox-full:latest");
            return fallback;
        }
        return profiles.getOrDefault(name, profiles.values().iterator().next());
    }

    /**
     * 校验工作区硬件规格是否命中 Nacos allowed-presets；未配置 presets 时仅校验上限护栏。
     * 返回解析后的 (memoryMb, cpus)；命中预设返回预设值，否则抛 IllegalArgumentException。
     */
    public int[] validateAndResolve(String profileName, Integer memoryMb, BigDecimal cpus) {
        ProfilePreset profile = resolveProfile(profileName);
        int mem = memoryMb != null ? memoryMb : profile.getDefaultMemoryMb();
        double cpu = cpus != null ? cpus.doubleValue() : profile.getDefaultCpus();
        if (profile.getAllowedPresets() == null || profile.getAllowedPresets().isEmpty()) {
            if (mem <= profile.getDefaultMemoryMb() && cpu <= profile.getDefaultCpus()) {
                return new int[]{mem, (int) Math.round(cpu * 10)};
            }
            throw new IllegalArgumentException(String.format(
                    "硬件规格超限: memoryMb=%d (max %d), cpus=%.1f (max %.1f)",
                    mem, profile.getDefaultMemoryMb(), cpu, profile.getDefaultCpus()));
        }
        for (Map<String, Object> preset : profile.getAllowedPresets()) {
            int pm = ((Number) preset.getOrDefault("memoryMb", 0)).intValue();
            double pc = ((Number) preset.getOrDefault("cpus", 0.0)).doubleValue();
            if (mem == pm && Math.abs(cpu - pc) < 0.01) {
                return new int[]{mem, (int) Math.round(cpu * 10)};
            }
        }
        throw new IllegalArgumentException(String.format(
                "硬件规格不在允许档位内: memoryMb=%d cpus=%.1f（允许档位见 Nacos agent.sandbox.profiles.%s.allowed-presets）",
                mem, cpu, profileName != null ? profileName : "full"));
    }

    @Getter
    @Setter
    public static class ProfilePreset {
        private int defaultMemoryMb = 2048;
        private double defaultCpus = 2.0;
        private List<Map<String, Object>> allowedPresets = new ArrayList<>();
        private String image = "sunshine-sandbox-full:latest";
    }

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
