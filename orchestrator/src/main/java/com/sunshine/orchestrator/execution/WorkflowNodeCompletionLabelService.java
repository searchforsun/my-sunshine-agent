package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.config.AgentPromptProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Workflow / Plan 节点完成态摘要 — SSOT：Nacos agent.timeline.workflow-node-completion */
@Service
@RefreshScope
@RequiredArgsConstructor
public class WorkflowNodeCompletionLabelService {

    private final AgentPromptProperties agentPromptProperties;

    @PostConstruct
    void init() {
        WorkflowNodeCompletionLabels.bind(this);
    }

    public String nodeComplete(String displayName) {
        return apply(timeline().getComplete(), "{displayName}", displayName);
    }

    public String hitCount(String hitCount) {
        return apply(timeline().getHitCount(), "{hitCount}", hitCount);
    }

    public String skipped() {
        return textOrDefault(timeline().getSkipped(), "已跳过");
    }

    public String skippedWithReason(String reason) {
        return apply(timeline().getSkippedWithReason(), "{reason}", reason);
    }

    public String retrySuccess(int attemptCount) {
        return apply(timeline().getRetrySuccess(), "{attemptCount}", String.valueOf(attemptCount));
    }

    public String retryFailedSuffix(int attemptCount) {
        return apply(timeline().getRetryFailedSuffix(), "{attemptCount}", String.valueOf(attemptCount));
    }

    public String nodeFailed() {
        return textOrDefault(timeline().getNodeFailed(), "节点执行失败");
    }

    public String attemptComplete() {
        return textOrDefault(timeline().getAttemptComplete(), "完成");
    }

    public String attemptFailed(String error) {
        return apply(timeline().getAttemptFailed(), "{error}", error);
    }

    private AgentPromptProperties.WorkflowNodeCompletionTimeline timeline() {
        AgentPromptProperties.WorkflowNodeCompletionTimeline cfg =
                agentPromptProperties.timelineOrDefault().getWorkflowNodeCompletion();
        return cfg != null ? cfg : new AgentPromptProperties.WorkflowNodeCompletionTimeline();
    }

    private static String apply(String template, String placeholder, String value) {
        if (!StringUtils.hasText(template)) {
            return value != null ? value : "";
        }
        return template.strip().replace(placeholder, value != null ? value : "");
    }

    private static String textOrDefault(String value, String fallback) {
        return StringUtils.hasText(value) ? value.strip() : fallback;
    }
}
