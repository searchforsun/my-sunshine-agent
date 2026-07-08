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
}
