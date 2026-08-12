package com.sunshine.orchestrator.sandbox;

import com.sunshine.orchestrator.config.AgentPromptProperties;
import com.sunshine.orchestrator.prompt.TimelinePromptCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 沙箱工具时间线主行文案 — SSOT：Nacos {@code agent.timeline.sandbox}。
 * 将 path / pattern / command 注入 after/active，避免仅显示「读文件完成」。
 */
@Service
@RefreshScope
@RequiredArgsConstructor
public class SandboxTimelineLabelService {

    private static final int VALUE_MAX = 96;

    private final TimelinePromptCatalog timelinePromptCatalog;

    public boolean isSandboxTool(String toolId) {
        return toolId != null && SandboxIds.ALL.contains(toolId);
    }

    public String after(String toolId, String displayName, Map<String, ?> input) {
        AgentPromptProperties.SandboxTimeline tpl = template();
        Map<String, String> vars = vars(displayName, input);
        String path = vars.get("path");
        String pattern = vars.get("pattern");
        String command = vars.get("command");
        String url = vars.get("url");
        String query = vars.get("query");
        String chosen = switch (toolId != null ? toolId : "") {
            case SandboxIds.READ -> hasText(path) ? tpl.getReadAfter() : null;
            case SandboxIds.WRITE -> hasText(path) ? tpl.getWriteAfter() : null;
            case SandboxIds.EDIT -> hasText(path) ? tpl.getEditAfter() : null;
            case SandboxIds.GLOB -> {
                if (!hasText(pattern)) {
                    yield null;
                }
                // 有 path（含裸 /skills|/workspace）则展示完整搜索根，与结果相对路径可拼绝对路径
                yield hasText(path) ? tpl.getGlobAfterWithPath() : tpl.getGlobAfter();
            }
            case SandboxIds.GREP -> {
                if (!hasText(pattern)) {
                    yield null;
                }
                // 搜索内容：结果行已含相对路径，主行不夹搜索根
                yield tpl.getGrepAfter();
            }
            case SandboxIds.EXEC -> hasText(command) ? tpl.getExecAfter() : null;
            case SandboxIds.WEBFETCH -> hasText(url) ? tpl.getWebfetchAfter() : null;
            case SandboxIds.WEBSEARCH -> hasText(query) ? tpl.getWebsearchAfter() : null;
            default -> null;
        };
        if (!hasText(chosen)) {
            chosen = tpl.getAfterFallback();
        }
        return apply(chosen, vars);
    }

    /**
     * 读文件主行：{fileName} 或 {fileName} L{a}-{b}。
     * 工具名（displayName）由前端 label 展示，此处仅补行范围便于前端定位与上下文感知。
     */
    public String readAfter(String displayName, Map<String, ?> input, String rawText) {
        AgentPromptProperties.SandboxTimeline tpl = template();
        Map<String, String> vars = vars(displayName, input);
        String path = vars.get("path");
        if (!hasText(path)) {
            return tpl.getAfterFallback();
        }
        Integer offset = asInteger(input, "offset");
        int lines = countLines(rawText);
        String base = apply(tpl.getReadAfter(), vars);
        String lineRange = lineRangeText(offset, lines);
        return hasText(lineRange) ? base + " " + lineRange : base;
    }

    /** L{a}-{b}（部分读取）或 L1-{n}（读全部）；offset 缺省视为从第 1 行读起 */
    static String lineRangeText(Integer offset, int lines) {
        if (lines <= 0) {
            return "";
        }
        int start = offset != null && offset > 0 ? offset : 1;
        int end = start + lines - 1;
        return "L" + start + "-" + end;
    }

    static int countLines(String text) {
        if (!hasText(text)) {
            return 0;
        }
        int n = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                n++;
            }
        }
        // 末尾换行不产生新行
        return text.endsWith("\n") ? n - 1 : n;
    }

    public String active(String toolId, String displayName, Map<String, ?> input) {
        AgentPromptProperties.SandboxTimeline tpl = template();
        Map<String, String> vars = vars(displayName, input);
        String path = vars.get("path");
        String pattern = vars.get("pattern");
        String command = vars.get("command");
        String url = vars.get("url");
        String query = vars.get("query");
        // write/edit：无 path 时用「…」占位，参数流阶段也能显示「正在写入 …」
        String chosen = switch (toolId != null ? toolId : "") {
            case SandboxIds.READ -> hasText(path) ? tpl.getReadActive() : null;
            case SandboxIds.WRITE -> {
                if (!hasText(path)) {
                    vars.put("path", "…");
                    vars.put("displayPath", "…");
                    vars.put("headerPath", "…");
                    vars.put("fileName", "…");
                }
                yield tpl.getWriteActive();
            }
            case SandboxIds.EDIT -> {
                if (!hasText(path)) {
                    vars.put("path", "…");
                    vars.put("displayPath", "…");
                    vars.put("headerPath", "…");
                    vars.put("fileName", "…");
                }
                yield tpl.getEditActive();
            }
            case SandboxIds.GLOB -> hasText(pattern) ? tpl.getGlobActive() : null;
            case SandboxIds.GREP -> hasText(pattern) ? tpl.getGrepActive() : null;
            case SandboxIds.EXEC -> hasText(command) ? tpl.getExecActive() : null;
            case SandboxIds.WEBFETCH -> hasText(url) ? tpl.getWebfetchActive() : null;
            case SandboxIds.WEBSEARCH -> hasText(query) ? tpl.getWebsearchActive() : null;
            default -> null;
        };
        if (!hasText(chosen)) {
            return null;
        }
        return apply(chosen, vars);
    }

    private AgentPromptProperties.SandboxTimeline template() {
        return timelinePromptCatalog.sandbox();
    }

    static Map<String, String> vars(String displayName, Map<String, ?> input) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("displayName", displayName != null ? displayName : "");
        String path = str(input, "path");
        map.put("path", clip(path));
        map.put("fileName", clip(fileName(path)));
        map.put("headerPath", clip(headerPath(path)));
        map.put("displayPath", clip(displayPath(path)));
        map.put("pattern", clip(str(input, "pattern")));
        // command 保留全文：主行由前端单行省略，展开区展示完整命令
        map.put("command", full(str(input, "command")));
        map.put("cwd", clip(str(input, "cwd")));
        map.put("url", clip(str(input, "url")));
        map.put("query", clip(str(input, "query")));
        return map;
    }

    private static String str(Map<String, ?> input, String key) {
        if (input == null || key == null) {
            return "";
        }
        Object v = input.get(key);
        return v != null ? String.valueOf(v).strip() : "";
    }

    private static Integer asInteger(Map<String, ?> input, String key) {
        if (input == null || key == null) {
            return null;
        }
        Object v = input.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String fileName(String path) {
        if (!hasText(path)) {
            return "";
        }
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 && slash < normalized.length() - 1
                ? normalized.substring(slash + 1)
                : normalized;
    }

    /** 主行展示：文件 → 文件名；目录/jail 根 → 保留绝对路径 */
    static String headerPath(String path) {
        if (!hasText(path)) {
            return "";
        }
        String norm = path.strip().replace('\\', '/').replaceAll("/+$", "");
        if ("/skills".equals(norm) || "/workspace".equals(norm)) {
            return norm;
        }
        String base = fileName(norm);
        if (!base.matches(".*\\.[^./]+$")) {
            return norm;
        }
        return base;
    }

    /** glob 结果路径推断搜索根（工具未传 path 时） */
    static String inferSearchRootFromPaths(String raw) {
        if (!hasText(raw)) {
            return "";
        }
        String[] lines = raw.split("\n");
        boolean anySkills = false;
        boolean anyWorkspace = false;
        boolean nonSkills = false;
        boolean nonWorkspace = false;
        for (String line : lines) {
            String t = line != null ? line.strip() : "";
            if (!t.startsWith("/skills") && !t.startsWith("/workspace")) {
                continue;
            }
            if (t.equals("/skills") || t.startsWith("/skills/")) {
                anySkills = true;
            } else {
                nonSkills = true;
            }
            if (t.equals("/workspace") || t.startsWith("/workspace/")) {
                anyWorkspace = true;
            } else {
                nonWorkspace = true;
            }
        }
        if (anySkills && !nonSkills) {
            return "/skills";
        }
        if (anyWorkspace && !nonWorkspace) {
            return "/workspace";
        }
        return "";
    }

    /** 从 glob after 文案解析 · /skills… 搜索根 */
    static String extractSearchRootFromAfter(String afterSummary) {
        if (!hasText(afterSummary)) {
            return "";
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("[·•]\\s*(/(?:workspace|skills)(?:/[^\\s·•]*)?)\\s*$")
                .matcher(afterSummary.strip());
        return m.find() ? m.group(1) : "";
    }

    /** 去掉 /skills|/workspace 前缀的相对路径；任务工作区再剥 wt-xxx/ */
    static String displayPath(String path) {
        if (!hasText(path)) {
            return "";
        }
        String normalized = path.strip().replace('\\', '/');
        if ("/skills".equals(normalized) || "/workspace".equals(normalized)) {
            return "";
        }
        if (normalized.startsWith("/skills/")) {
            return normalized.substring("/skills/".length());
        }
        if (normalized.startsWith("/workspace/")) {
            String rest = normalized.substring("/workspace/".length());
            // /workspace/wt-xxx/docs/a.md → docs/a.md
            if (rest.matches("wt-[a-zA-Z0-9]+(/.*)?")) {
                int slash = rest.indexOf('/');
                return slash >= 0 ? rest.substring(slash + 1) : "";
            }
            return rest;
        }
        return normalized;
    }

    static String clip(String value) {
        if (!hasText(value)) {
            return "";
        }
        String normalized = value.strip().replace('\n', ' ');
        if (normalized.length() <= VALUE_MAX) {
            return normalized;
        }
        return normalized.substring(0, VALUE_MAX) + "…";
    }

    /** 不截断；仅 strip（命令展开需全文） */
    static String full(String value) {
        return hasText(value) ? value.strip() : "";
    }

    private static String apply(String template, Map<String, String> vars) {
        if (!hasText(template)) {
            return "";
        }
        String result = template.strip();
        for (Map.Entry<String, String> e : vars.entrySet()) {
            result = result.replace("{" + e.getKey() + "}", e.getValue() != null ? e.getValue() : "");
        }
        return result;
    }

    private static boolean hasText(String value) {
        return StringUtils.hasText(value);
    }
}
