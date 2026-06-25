package com.sunshine.skill.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class ContentMd5Test {

    @Test
    void hexFromText_isStable() {
        String md5 = ContentMd5.hexFromText("hello");
        assertThat(md5).hasSize(32);
        assertThat(md5).isEqualTo(ContentMd5.hexFromText("hello"));
    }

    @Test
    void hexFromBase64_matchesRawBytes() {
        byte[] raw = "binary".getBytes(StandardCharsets.UTF_8);
        String fromBase64 = ContentMd5.hexFromBase64(Base64.getEncoder().encodeToString(raw));
        assertThat(fromBase64).isEqualTo(ContentMd5.hex(raw));
    }
}
