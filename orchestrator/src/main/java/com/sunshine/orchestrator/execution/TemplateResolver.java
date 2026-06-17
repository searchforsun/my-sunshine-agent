package com.sunshine.orchestrator.execution;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析节点参数中的 {{nodeId.field}} 模板
 */
public final class TemplateResolver {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z0-9_.]+)}}");

    private TemplateResolver() {
    }

    public static String resolve(String template, WorkflowContext ctx) {
        if (template == null || template.isBlank() || ctx == null) {
            return template != null ? template : "";
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String replacement = ctx.resolvePath(matcher.group(1));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
