package com.sunshine.orchestrator.plan;

import com.sunshine.orchestrator.client.StreamToken;
import java.util.List;

/** Plan 用户确认阻塞等待结果 */
public record PlanApprovalWaitResult(
        PlanApprovalUserAction action,
        String modificationHint,
        List<StreamToken> tokens) {
}
