package com.sunshine.rag.ocr;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DashScopeOcrServiceTest {

    @Test
    void extractOcrText_parsesDocumentParsingResponse() {
        Map<String, Object> response = Map.of(
                "output", Map.of(
                        "choices", List.of(
                                Map.of(
                                        "message", Map.of(
                                                "content", List.of(
                                                        Map.of("text", "# 标题\n\n正文")))))));
        assertEquals("# 标题\n\n正文", DashScopeOcrService.extractOcrText(response));
    }

    @Test
    void extractOcrText_emptyWhenMalformed() {
        assertEquals("", DashScopeOcrService.extractOcrText(null));
        assertEquals("", DashScopeOcrService.extractOcrText(Map.of("output", Map.of())));
    }
}
