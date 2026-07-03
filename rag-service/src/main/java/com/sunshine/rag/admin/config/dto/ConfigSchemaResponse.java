package com.sunshine.rag.admin.config.dto;

import com.sunshine.rag.admin.config.EffectiveRagConfig;

import java.util.List;

public record ConfigSchemaResponse(List<ConfigScopeGroup> scopes, EffectiveRagConfig effective) {
}
