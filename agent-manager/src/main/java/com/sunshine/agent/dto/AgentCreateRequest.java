package com.sunshine.agent.dto;

import java.util.List;

public record AgentCreateRequest(
        String id,
        String displayName,
        String description,
        String systemPrompt,
        List<String> skillIds,
        List<String> toolIds
) {
}
