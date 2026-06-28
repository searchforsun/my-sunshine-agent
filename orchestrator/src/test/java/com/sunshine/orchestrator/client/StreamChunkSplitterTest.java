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

    @Test
    @DisplayName("分段 content 切分保留 segmentId")
    void segmentedContent_preservesSegmentId() {
        String text = "我先为您查询待办任务和财务待办消息，同时检索合规制度。";
        List<StreamToken> out = collect(StreamToken.contentInSegment("content-1", text), 12);

        assertThat(out.size()).isGreaterThan(1);
        assertThat(out).allMatch(t -> "content-1".equals(t.segmentId()));
        assertThat(out.stream().map(StreamToken::text).reduce("", String::concat)).isEqualTo(text);
    }

    @Test
    @DisplayName("content_start/end 不拆分")
    void lifecycleTokens_passThrough() {
        List<StreamToken> out = new ArrayList<>();
        StreamChunkSplitter.split(reactor.core.publisher.Flux.just(
                StreamToken.contentStart("content-1", "think"),
                StreamToken.contentEnd("content-1")), 8).subscribe(out::add);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).isContentStart()).isTrue();
        assertThat(out.get(1).isContentEnd()).isTrue();
    }

    private static List<StreamToken> collect(StreamToken token, int maxChars) {
        List<StreamToken> out = new ArrayList<>();
        StreamChunkSplitter.split(reactor.core.publisher.Flux.just(token), maxChars).subscribe(out::add);
        return out;
    }
}
