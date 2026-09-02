package com.sunshine.orchestrator.agent;

import java.util.List;

/** POST .../decisions/{token}/resolve 请求体；skip=true 时忽略 answers。 */
public record ResolveDecisionRequest(List<DecisionAnswer> answers, Boolean skip) {
}
