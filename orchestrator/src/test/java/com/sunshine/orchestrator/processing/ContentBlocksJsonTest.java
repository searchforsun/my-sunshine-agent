package com.sunshine.orchestrator.processing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContentBlocksJsonTest {

    @Test
    void flattenToPlainText_joinsSegments() {
        String json = """
                [{"segmentId":"content-1","afterStepId":"think","text":"第一段"},{"segmentId":"content-2","afterStepId":"think-2","text":"第二段"}]
                """;
        assertThat(ContentBlocksJson.flattenToPlainText(json)).isEqualTo("第一段\n\n第二段");
    }

    @Test
    void resolveBody_prefersContent() {
        assertThat(ContentBlocksJson.resolveBody("主正文",
                "[{\"segmentId\":\"content-1\",\"afterStepId\":\"think\",\"text\":\"块\"}]"))
                .isEqualTo("主正文");
    }

    @Test
    void resolveBody_fallsBackToBlocks() {
        String json = "[{\"segmentId\":\"content-1\",\"afterStepId\":\"think\",\"text\":\"仅块内\"}]";
        assertThat(ContentBlocksJson.resolveBody("", json)).isEqualTo("仅块内");
        assertThat(ContentBlocksJson.resolveBody(null, json)).isEqualTo("仅块内");
    }

    @Test
    void joinTexts_skipsBlank() {
        assertThat(ContentBlocksJson.joinTexts(List.of(
                new ContentBlock("a", "think", "  x  "),
                new ContentBlock("b", "think", "  "),
                new ContentBlock("c", "think", "y")))).isEqualTo("x\n\ny");
    }
}
