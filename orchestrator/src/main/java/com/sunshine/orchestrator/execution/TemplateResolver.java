package com.sunshine.orchestrator.execution;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析节点参数中的 {{nodeId.path[0].field}} 模板，支持嵌套 JSONPath 取值
 */
public final class TemplateResolver {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^}]+)}}");

    private TemplateResolver() {
    }

    /** 模板解析：{{nodeId.path}} 替换为 resolvePath().render() */
    public static String resolve(String template, WorkflowContext ctx) {
        if (template == null || template.isBlank() || ctx == null) {
            return template != null ? template : "";
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String replacement = ctx.resolvePath(matcher.group(1).trim()).render();
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** 直接取结构化值（供 tool inputs 等需要 TypedValue 的场景） */
    public static TypedValue resolveTyped(String path, WorkflowContext ctx) {
        if (path == null || path.isBlank() || ctx == null) {
            return TypedValue.fromJson(com.fasterxml.jackson.databind.node.NullNode.getInstance());
        }
        String trimmed = path.trim();
        if (trimmed.startsWith("{{") && trimmed.endsWith("}}")) {
            trimmed = trimmed.substring(2, trimmed.length() - 2).trim();
        }
        return ctx.resolvePath(trimmed);
    }
}
