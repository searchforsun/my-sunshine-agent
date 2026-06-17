package com.sunshine.orchestrator.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StreamTokenCoalescerTest {

    @Test
    @DisplayName("reasoning 交错时不拆散 content token")
    void reasoningDoesNotFlushPartialContent() {
        List<StreamToken> out = collect(
                StreamToken.content("328"),
                StreamToken.reasoning("think"),
                StreamToken.content("0.5")
        );

        assertThat(out).hasSize(3);
        assertThat(out.get(0).text()).isEqualTo("328");
        assertThat(out.get(1).kind()).isEqualTo(StreamToken.KIND_REASONING);
        assertThat(out.get(2).text()).isEqualTo("0.5");
        assertThat(out.stream().filter(StreamToken::isContent).map(StreamToken::text).reduce("", String::concat))
                .isEqualTo("3280.5");
    }

    @Test
    @DisplayName("换行合并到前一个 content token")
    void mergesNewlineIntoPreviousContent() {
        List<StreamToken> out = collect(
                StreamToken.content("```python"),
                StreamToken.content("\n"),
                StreamToken.content("def"),
                StreamToken.reasoning("think"),
                StreamToken.content(" x")
        );

        assertThat(out).hasSize(4);
        assertThat(out.get(0).text()).isEqualTo("```python");
        assertThat(out.get(1).text()).isEqualTo("\ndef");
        assertThat(out.get(2).kind()).isEqualTo(StreamToken.KIND_REASONING);
        assertThat(out.get(3).text()).isEqualTo(" x");
        assertThat(out.stream().filter(StreamToken::isContent).map(StreamToken::text).reduce("", String::concat))
                .isEqualTo("```python\ndef x");
    }

    @Test
    @DisplayName("连续 content 逐 token 保序输出")
    void consecutiveContentPreservesOrder() {
        List<StreamToken> out = collect(
                StreamToken.content("3"),
                StreamToken.content("280"),
                StreamToken.content(".5")
        );
        assertThat(out).extracting(StreamToken::text).containsExactly("3", "280", ".5");
    }

    @Test
    @DisplayName("空格合并到前一个 content token")
    void mergesSpacesIntoPreviousContent() {
        List<StreamToken> out = collect(
                StreamToken.content("if"),
                StreamToken.content(" "),
                StreamToken.content("len")
        );

        assertThat(out).hasSize(2);
        assertThat(out.get(0).text()).isEqualTo("if");
        assertThat(out.get(1).text()).isEqualTo(" len");
    }

    @Test
    @DisplayName("流结束时刷新尾部 content")
    void flushesTrailingContent() {
        List<StreamToken> out = collect(StreamToken.content("a"), StreamToken.content("\n"));

        assertThat(out).hasSize(2);
        assertThat(out.stream().map(StreamToken::text).reduce("", String::concat)).isEqualTo("a\n");
    }

    private static List<StreamToken> collect(StreamToken... tokens) {
        List<StreamToken> out = new ArrayList<>();
        StreamTokenCoalescer.coalesce(reactor.core.publisher.Flux.fromArray(tokens)).subscribe(out::add);
        return out;
    }
}
