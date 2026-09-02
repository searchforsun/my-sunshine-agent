package com.sunshine.orchestrator.prompt;

/**
 * 对齐 prompt-manager {@code PromptCatalogEntry} JSON（camelCase）。
 */
public record PromptCatalogEntry(
        String id,
        String kind,
        String displayName,
        boolean enabled,
        int priority,
        int version,
        String contentText,
        String contentJson
) {
}
