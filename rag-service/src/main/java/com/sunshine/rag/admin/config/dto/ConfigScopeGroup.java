package com.sunshine.rag.admin.config.dto;

import java.util.List;

public record ConfigScopeGroup(
        String scope,
        String label,
        String dataId,
        String nacosPath,
        List<ConfigFieldSchema> fields) {
}
