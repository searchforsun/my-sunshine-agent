package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.config.AgentExecutionProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** answer prompt 上游占位解析 — 失败节点注入降级说明 */
@Component
public class UpstreamOutputResolver {

    private static final Pattern OUTPUT_PLACEHOLDER =
            Pattern.compile("\\{\\{([a-zA-Z0-9_.-]+)\\.output}}");

    private final AgentExecutionProperties executionProperties;

    public UpstreamOutputResolver(AgentExecutionProperties executionProperties) {
        this.executionProperties = executionProperties;
    }

    public String resolvePrompt(String template, WorkflowContext ctx, WorkflowDefinition def) {
        if (!StringUtils.hasText(template) || ctx == null) {
            return template != null ? template : "";
        }
        Matcher matcher = OUTPUT_PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String nodeId = matcher.group(1);
            String replacement = resolveOutput(nodeId, ctx, def);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return TemplateResolver.resolve(sb.toString(), ctx);
    }

    private String resolveOutput(String nodeId, WorkflowContext ctx, WorkflowDefinition def) {
        String direct = ctx.resolvePath(nodeId + ".output");
        if (StringUtils.hasText(direct)) {
            return direct;
        }
        String answer = ctx.node(nodeId).get("answer");
        if (StringUtils.hasText(answer)) {
            return answer;
        }
        WorkflowContext.NodeFailureInfo failure = ctx.nodeFailure(nodeId);
        if (failure == null) {
            return "";
        }
        String displayName = resolveDisplayName(nodeId, def);
        String line = executionProperties.getPlanWorkflow().getAnswer().getUpstreamFailureLine();
        if (!StringUtils.hasText(line)) {
            return "（" + displayName + " 执行失败：" + failure.error() + "）";
        }
        return line
                .replace("{{displayName}}", displayName)
                .replace("{{error}}", failure.error() != null ? failure.error() : "未知错误")
                .replace("{{attemptCount}}", String.valueOf(Math.max(failure.attemptCount(), 1)));
    }

    private static String resolveDisplayName(String nodeId, WorkflowDefinition def) {
        if (def == null) {
            return nodeId;
        }
        NodeSpec spec = def.node(nodeId);
        if (spec != null && StringUtils.hasText(spec.displayName())) {
            return spec.displayName().strip();
        }
        return nodeId;
    }
}
