package com.sunshine.orchestrator.workflow;

import com.sunshine.orchestrator.routing.WorkflowCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class WorkflowBindingParser {
    private static final Pattern HASH_PATTERN = Pattern.compile(
            "^#([\\w\\u4e00-\\u9fff-]+)(?:\\s+(.*)|\\s*)$", Pattern.DOTALL);

    private final WorkflowCatalog workflowCatalog;

    public WorkflowBindingOutcome parse(String userMessage) {
        if (!StringUtils.hasText(userMessage)) {
            return WorkflowBindingOutcome.none("");
        }
        Matcher matcher = HASH_PATTERN.matcher(userMessage.strip());
        if (!matcher.matches()) {
            return WorkflowBindingOutcome.none(userMessage.strip());
        }
        String token = matcher.group(1);
        String rest = matcher.group(2) != null ? matcher.group(2).strip() : "";
        if (!workflowCatalog.isKnownWorkflow(token)) {
            return WorkflowBindingOutcome.unknown(token);
        }
        return WorkflowBindingOutcome.bound(token.strip(), rest);
    }

    /**
     * L0 绑定：优先 Chat 请求体 workflowId（# chip 已解析），再回落正文 #mention。
     */
    public WorkflowBindingOutcome resolve(String userMessage, String clientWorkflowId) {
        if (StringUtils.hasText(clientWorkflowId)) {
            String workflowId = clientWorkflowId.strip();
            if (!workflowCatalog.isKnownWorkflow(workflowId)) {
                return WorkflowBindingOutcome.unknown(workflowId);
            }
            WorkflowBindingOutcome parsed = parse(userMessage);
            String effectiveQuery = parsed.bound() && workflowId.equals(parsed.workflowId())
                    ? parsed.effectiveQuery()
                    : effectiveQueryAfterMention(userMessage, workflowId);
            return WorkflowBindingOutcome.bound(workflowId, effectiveQuery);
        }
        return parse(userMessage);
    }

    private static String effectiveQueryAfterMention(String userMessage, String workflowId) {
        String trimmed = userMessage != null ? userMessage.strip() : "";
        String prefix = "#" + workflowId;
        if (trimmed.regionMatches(true, 0, prefix, 0, prefix.length())) {
            String rest = trimmed.substring(prefix.length()).strip();
            return StringUtils.hasText(rest) ? rest : trimmed;
        }
        return trimmed;
    }
}
