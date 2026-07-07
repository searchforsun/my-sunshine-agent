package com.sunshine.orchestrator.execution;

/** Workflow / Plan 节点完成态摘要静态入口 */
public final class WorkflowNodeCompletionLabels {

    private static volatile WorkflowNodeCompletionLabelService service;

    private WorkflowNodeCompletionLabels() {
    }

    public static void bind(WorkflowNodeCompletionLabelService labelService) {
        service = labelService;
    }

    public static String nodeComplete(String displayName) {
        return requireService().nodeComplete(displayName);
    }

    public static String hitCount(String hitCount) {
        return requireService().hitCount(hitCount);
    }

    public static String skipped() {
        return requireService().skipped();
    }

    public static String skippedWithReason(String reason) {
        return requireService().skippedWithReason(reason);
    }

    public static String retrySuccess(int attemptCount) {
        return requireService().retrySuccess(attemptCount);
    }

    public static String retryFailedSuffix(int attemptCount) {
        return requireService().retryFailedSuffix(attemptCount);
    }

    public static String nodeFailed() {
        return requireService().nodeFailed();
    }

    public static String attemptComplete() {
        return requireService().attemptComplete();
    }

    public static String attemptFailed(String error) {
        return requireService().attemptFailed(error);
    }

    private static WorkflowNodeCompletionLabelService requireService() {
        if (service == null) {
            throw new IllegalStateException("WorkflowNodeCompletionLabelService 未 bind");
        }
        return service;
    }
}
