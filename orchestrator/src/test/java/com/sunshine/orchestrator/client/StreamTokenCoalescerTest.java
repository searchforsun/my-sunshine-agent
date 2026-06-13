package com.sunshine.orchestrator.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StreamTokenCoalescerTest {

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
        assertThat(out.get(0).text()).isEqualTo("```python\n");
        assertThat(out.get(1).text()).isEqualTo("def");
        assertThat(out.get(2).kind()).isEqualTo(StreamToken.KIND_REASONING);
        assertThat(out.get(3).text()).isEqualTo(" x");
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
        assertThat(out.get(0).text()).isEqualTo("if ");
        assertThat(out.get(1).text()).isEqualTo("len");
    }

    @Test
    @DisplayName("流结束时刷新尾部 content")
    void flushesTrailingContent() {
        List<StreamToken> out = collect(StreamToken.content("a"), StreamToken.content("\n"));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).text()).isEqualTo("a\n");
    }

    private static List<StreamToken> collect(StreamToken... tokens) {
        List<StreamToken> out = new ArrayList<>();
        StreamTokenCoalescer.coalesce(reactor.core.publisher.Flux.fromArray(tokens)).subscribe(out::add);
        return out;
    }
}
