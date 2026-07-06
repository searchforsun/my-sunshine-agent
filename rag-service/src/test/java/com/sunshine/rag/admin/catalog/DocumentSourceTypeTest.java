package com.sunshine.rag.admin.catalog;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentSourceTypeTest {

    @Test
    void require_defaultsToMarkdown() {
        assertThat(DocumentSourceType.require(null)).isEqualTo(DocumentSourceType.MARKDOWN);
    }

    @Test
    void placeholder_differsByType() {
        assertThat(DocumentSourceType.TEXT.placeholder())
                .isNotEqualTo(DocumentSourceType.MARKDOWN.placeholder());
    }

    @Test
    void inlineEditable_onlyTextAndMarkdown() {
        assertThat(DocumentSourceType.MARKDOWN.inlineEditable()).isTrue();
        assertThat(DocumentSourceType.PDF.inlineEditable()).isFalse();
    }
}
