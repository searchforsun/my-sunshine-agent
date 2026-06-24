package com.sunshine.orchestrator.execution.retry;

import lombok.Getter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 单次 Workflow 执行运行时状态 — 节点失败、整单中断 */
@Getter
public class WorkflowRunSession {

    private boolean aborted;
    private OnFailureAction abortAction;
    private String abortReason;
    private boolean hasNodeFailures;
    private final List<String> failedNodeIds = new ArrayList<>();
    private final Map<String, Map<String, String>> partialOutputs = new LinkedHashMap<>();

    public void noteNodeSuccess(String nodeId, Map<String, String> outputs) {
        if (outputs != null && !outputs.isEmpty()) {
            partialOutputs.put(nodeId, new LinkedHashMap<>(outputs));
        }
    }

    public void noteNodeFailure(String nodeId) {
        hasNodeFailures = true;
        if (!failedNodeIds.contains(nodeId)) {
            failedNodeIds.add(nodeId);
        }
    }

    public void abort(OnFailureAction action, String reason) {
        this.aborted = true;
        this.abortAction = action;
        this.abortReason = reason;
    }

    public boolean isFallbackReact() {
        return aborted && abortAction == OnFailureAction.FALLBACK_REACT;
    }

    public boolean isFailFast() {
        return aborted && abortAction == OnFailureAction.FAIL_FAST;
    }
}
