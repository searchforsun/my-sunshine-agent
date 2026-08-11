package com.sunshine.orchestrator.agent;

/** POST .../decisions/{token}/resolve 请求体 */
public record ResolveDecisionRequest(String choice, String customInput) {
}
