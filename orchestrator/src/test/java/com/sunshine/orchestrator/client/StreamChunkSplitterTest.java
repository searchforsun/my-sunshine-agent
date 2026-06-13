package com.sunshine.orchestrator.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StreamChunkSplitterTest {

    @Test
    @DisplayName("超大 content 切分为多段")
    void splitsLargeContent() {
        String text = "RAG（Retrieval-Augmented Generation）是一种将外部知识检索与大语言模型结合的方法。";
        List<StreamToken> out = collect(StreamToken.content(text), 24);

        assertThat(out.size()).isGreaterThan(1);
        assertThat(out.stream().map(StreamToken::text).reduce("", String::concat)).isEqualTo(text);
        assertThat(out).allMatch(t -> t.text().length() <= 24);
    }

    @Test
    @DisplayName("小 token 不拆分")
    void smallTokenUnchanged() {
        List<StreamToken> out = collect(StreamToken.content("hello"), 24);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).text()).isEqualTo("hello");
    }

    @Test
    @DisplayName("maxChars=0 禁用拆分")
    void disabledWhenZero() {
        String text = "a".repeat(100);
        List<StreamToken> out = collect(StreamToken.content(text), 0);
        assertThat(out).hasSize(1);
    }

    private static List<StreamToken> collect(StreamToken token, int maxChars) {
        List<StreamToken> out = new ArrayList<>();
        StreamChunkSplitter.split(reactor.core.publisher.Flux.just(token), maxChars).subscribe(out::add);
        return out;
    }
}
