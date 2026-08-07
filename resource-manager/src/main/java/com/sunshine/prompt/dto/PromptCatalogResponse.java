package com.sunshine.prompt.dto;

import java.util.List;

/** GET /api/prompts/catalog 响应体 */
public record PromptCatalogResponse(
        long catalogVersion,
        List<PromptCatalogEntry> entries
) {
}
