package com.sunshine.orchestrator.execution;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

/** Workflow / Plan 节点完成态摘要 */
@Service
public class WorkflowNodeCompletionLabelService {

    @PostConstruct
    void init() {
        WorkflowNodeCompletionLabels.bind(this);
    }

    public String nodeComplete(String displayName) {
        return WorkflowTimelineLabels.apply(WorkflowTimelineLabels.COMPLETE, "{displayName}", displayName);
    }

    public String hitCount(String hitCount) {
        return WorkflowTimelineLabels.apply(WorkflowTimelineLabels.HIT_COUNT, "{hitCount}", hitCount);
    }

    public String skipped() {
        return WorkflowTimelineLabels.SKIPPED;
    }

    public String skippedWithReason(String reason) {
        return WorkflowTimelineLabels.apply(WorkflowTimelineLabels.SKIPPED_WITH_REASON, "{reason}", reason);
    }

    public String retrySuccess(int attemptCount) {
        return WorkflowTimelineLabels.apply(
                WorkflowTimelineLabels.RETRY_SUCCESS, "{attemptCount}", String.valueOf(attemptCount));
    }

    public String retryFailedSuffix(int attemptCount) {
        return WorkflowTimelineLabels.apply(
                WorkflowTimelineLabels.RETRY_FAILED_SUFFIX, "{attemptCount}", String.valueOf(attemptCount));
    }

    public String nodeFailed() {
        return WorkflowTimelineLabels.NODE_FAILED;
    }

    public String attemptComplete() {
        return WorkflowTimelineLabels.ATTEMPT_COMPLETE;
    }

    public String attemptFailed(String error) {
        return WorkflowTimelineLabels.apply(WorkflowTimelineLabels.ATTEMPT_FAILED, "{error}", error);
    }
}
