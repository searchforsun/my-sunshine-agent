package com.sunshine.agent.dto;

import java.util.List;

public record AgentUpdateRequest(
        String displayName,
        String description,
        String systemPrompt,
        List<String> skillIds,
        List<String> toolIds
) {
}
