package com.sunshine.rag.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentVersionTimeTest {

    @Test
    void uniqueKeyBumpsWhenSameSecondExists() {
        String existing = DocumentVersionTime.now();
        String next = DocumentVersionTime.uniqueKey(List.of(existing));
        assertThat(next).isNotEqualTo(existing);
        assertThat(DocumentVersionTime.toInstant(next).getEpochSecond())
                .isEqualTo(DocumentVersionTime.toInstant(existing).getEpochSecond() + 1);
    }

    @Test
    void uniqueKeyKeepsFormat() {
        String next = DocumentVersionTime.uniqueKey(List.of());
        assertThat(next).matches("\\d{14}");
    }
}
