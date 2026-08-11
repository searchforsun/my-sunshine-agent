package com.sunshine.orchestrator.agent;

import java.util.List;

/** POST .../decisions/{token}/resolve 请求体 */
public record ResolveDecisionRequest(List<DecisionAnswer> answers) {
}
