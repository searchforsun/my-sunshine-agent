package com.sunshine.orchestrator.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * ReAct mode-overlay 拼装：{@code mode-overlay.react} + enabled {@code react-fragment}（按 sortOrder）。
 */
@Slf4j
public final class ReactOverlayAssembler {

    public static final String REACT_OVERLAY_ID = "mode-overlay.react";
    public static final String REACT_FRAGMENT_KIND = "react-fragment";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ReactOverlayAssembler() {
    }

    /**
     * base = snapshot.text(mode-overlay.react)（缺省空串）；再追加 attachTo 匹配的 enabled fragments，按 sortOrder 升序。
     * 无 fragment 时仅返回 base。
     */
    public static String assemble(PromptCatalogSnapshot snapshot) {
        if (snapshot == null) {
            return "";
        }
        String base = snapshot.entry(REACT_OVERLAY_ID)
                .map(e -> e.contentText() != null ? e.contentText().strip() : "")
                .orElseGet(() -> {
                    log.warn("[ReactOverlayAssembler] catalog missing id={}", REACT_OVERLAY_ID);
                    return "";
                });
        List<PromptCatalogEntry> fragments = enabledFragments(snapshot, REACT_OVERLAY_ID);
        if (fragments.isEmpty()) {
            return base;
        }
        StringBuilder sb = new StringBuilder(base);
        for (PromptCatalogEntry fragment : fragments) {
            String text = fragment.contentText();
            if (text == null || text.isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(text.strip());
        }
        return sb.toString();
    }

    static List<PromptCatalogEntry> enabledFragments(PromptCatalogSnapshot snapshot, String attachTo) {
        List<PromptCatalogEntry> out = new ArrayList<>();
        for (PromptCatalogEntry entry : snapshot.byId().values()) {
            if (entry == null || !entry.enabled() || !REACT_FRAGMENT_KIND.equals(entry.kind())) {
                continue;
            }
            if (!attachTo.equals(parseAttachTo(entry))) {
                continue;
            }
            out.add(entry);
        }
        out.sort(Comparator.comparingInt(ReactOverlayAssembler::parseSortOrder)
                .thenComparing(PromptCatalogEntry::id, Comparator.nullsLast(String::compareTo)));
        return out;
    }

    static String parseAttachTo(PromptCatalogEntry entry) {
        JsonNode root = parseJson(entry.contentJson());
        if (root == null) {
            return "";
        }
        JsonNode node = root.get("attachTo");
        if (node == null || node.isNull() || !node.isTextual()) {
            return "";
        }
        String value = node.asText();
        return value != null ? value.strip() : "";
    }

    static int parseSortOrder(PromptCatalogEntry entry) {
        JsonNode root = parseJson(entry.contentJson());
        if (root == null || !root.has("sortOrder") || root.get("sortOrder").isNull()) {
            return 0;
        }
        JsonNode node = root.get("sortOrder");
        if (node.isNumber()) {
            return node.asInt(0);
        }
        if (node.isTextual()) {
            try {
                return Integer.parseInt(node.asText().strip());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static JsonNode parseJson(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(contentJson.strip());
        } catch (Exception e) {
            log.warn("[ReactOverlayAssembler] bad contentJson: {}", e.getMessage());
            return null;
        }
    }
}
