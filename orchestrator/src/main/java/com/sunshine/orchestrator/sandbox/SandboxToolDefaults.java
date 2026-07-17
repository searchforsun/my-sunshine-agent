package com.sunshine.orchestrator.sandbox;

import com.sunshine.orchestrator.config.AgentSandboxProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 沙箱六工具默认定义 — Nacos 未配置时的 Java 兜底 */
public final class SandboxToolDefaults {

    private SandboxToolDefaults() {}

    public static Map<String, AgentSandboxProperties.ToolDef> all() {
        Map<String, AgentSandboxProperties.ToolDef> tools = new LinkedHashMap<>();
        tools.put(SandboxIds.READ, read());
        tools.put(SandboxIds.WRITE, write());
        tools.put(SandboxIds.EDIT, edit());
        tools.put(SandboxIds.GLOB, glob());
        tools.put(SandboxIds.GREP, grep());
        tools.put(SandboxIds.EXEC, exec());
        return tools;
    }

    private static AgentSandboxProperties.ToolDef read() {
        AgentSandboxProperties.ToolDef def = base(
                "读文件",
                "读取沙箱内文本文件（/skills/{skillId}/... 或 /workspace；勿对目录调用）");
        def.getProperties().put("path", param("string",
                "文件路径（须 /skills/{skillId}/... 或 /workspace/...；禁止旧路径 /skill）"));
        def.getProperties().put("offset", param("integer", "起始行（可选）"));
        def.getProperties().put("limit", param("integer", "读取行数上限（可选）"));
        def.setRequired(List.of("path"));
        return def;
    }

    private static AgentSandboxProperties.ToolDef write() {
        AgentSandboxProperties.ToolDef def = base(
                "写文件",
                "仅新建工作区文件（仅 /workspace；已存在则失败，请改用 edit 或换路径）");
        def.getProperties().put("path", param("string",
                "写入路径（仅 /workspace）；仅允许新建，禁止覆盖已有文件"));
        def.getProperties().put("content", param("string", "新建文件全文内容"));
        def.setRequired(List.of("path", "content"));
        return def;
    }

    private static AgentSandboxProperties.ToolDef edit() {
        AgentSandboxProperties.ToolDef def = base(
                "编辑文件",
                "精确替换已有工作区文件中的唯一子串（仅 /workspace）");
        def.getProperties().put("path", param("string", "已有文件路径（仅 /workspace）"));
        def.getProperties().put("old_string", param("string", "待替换的精确原文（须在文件中唯一出现）"));
        def.getProperties().put("new_string", param("string", "替换后的文本"));
        def.setRequired(List.of("path", "old_string", "new_string"));
        return def;
    }

    private static AgentSandboxProperties.ToolDef glob() {
        AgentSandboxProperties.ToolDef def = base(
                "查找文件",
                "在沙箱 jail 内按 glob 查找文件路径（优先收窄 path/pattern）");
        def.getProperties().put("pattern", param("string", "glob 模式，如 **/*.py；尽量收窄"));
        def.getProperties().put("path", param("string",
                "搜索根（可选）：须为 /skills/{skillId}/... 或 /workspace；禁止 /skill；缺省搜全部 jail"));
        def.setRequired(List.of("pattern"));
        return def;
    }

    private static AgentSandboxProperties.ToolDef grep() {
        AgentSandboxProperties.ToolDef def = base(
                "搜索内容",
                "在沙箱 jail 内按正则搜索文件内容（须提供 pattern）");
        def.getProperties().put("pattern", param("string", "搜索正则（必填）；避免空模式"));
        def.getProperties().put("path", param("string",
                "搜索路径（可选）：须为 /skills/{skillId}/... 或 /workspace；禁止 /skill"));
        def.getProperties().put("glob", param("string", "文件名 glob 过滤（可选）"));
        def.setRequired(List.of("pattern"));
        return def;
    }

    private static AgentSandboxProperties.ToolDef exec() {
        AgentSandboxProperties.ToolDef def = base(
                "执行命令",
                "在沙箱容器内执行 shell（破坏性命令会被拒绝；只读命令通常免 HITL）");
        def.getProperties().put("command", param("string",
                "shell 命令；禁止 rm -rf /、管道下载执行、mkfs 等破坏性操作"));
        def.getProperties().put("cwd", param("string",
                "工作目录（可选，默认 /workspace；须在 /skills/{skillId}/... 或 /workspace）"));
        def.getProperties().put("timeout_sec", param("integer", "超时秒数（可选，默认 30）"));
        def.setRequired(List.of("command"));
        return def;
    }

    private static AgentSandboxProperties.ToolDef base(String displayName, String description) {
        AgentSandboxProperties.ToolDef def = new AgentSandboxProperties.ToolDef();
        def.setDisplayName(displayName);
        def.setDescription(description);
        def.setProperties(new LinkedHashMap<>());
        def.setRequired(new ArrayList<>());
        return def;
    }

    private static AgentSandboxProperties.ParamDef param(String type, String description) {
        AgentSandboxProperties.ParamDef param = new AgentSandboxProperties.ParamDef();
        param.setType(type);
        param.setDescription(description);
        return param;
    }
}
