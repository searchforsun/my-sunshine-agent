package com.sunshine.agent.dto;

import java.util.List;
import java.util.Map;

/**
 * Agent Card 解析结果，用于前端外部智能体注册时预填表单。
 */
public record AgentCardPreFill(
        String displayName,
        String description,
        String version,
        List<String> skills,
        String endpointUrl,
        String error
) {
    public static AgentCardPreFill error(String message) {
        return new AgentCardPreFill(null, null, null, List.of(), null, message);
    }
}
