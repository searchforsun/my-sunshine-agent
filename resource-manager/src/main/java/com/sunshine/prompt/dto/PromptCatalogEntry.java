package com.sunshine.prompt.dto;

/** 运行时 Catalog 精简条目（仅 enabled + active published） */
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
