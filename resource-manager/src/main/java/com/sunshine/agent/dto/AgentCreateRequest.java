package com.sunshine.agent.dto;

import java.util.List;

public record AgentCreateRequest(
        String id,
        String displayName,
        String description,
        String systemPrompt,
        List<String> skillIds,
        List<String> toolIds,
        String source,
        String agentCardUrl,
        String authConfigJson,
        String endpointOverride,
        List<String> kbScope,
        String dataScopeJson,
        String permissionsJson,
        String modelConfigJson,
        Integer maxIters,
        Integer maxHandoffs,
        String kind,
        String bizScene
) {
}
