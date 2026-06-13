package com.sunshine.orchestrator.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StreamDeltaNormalizerTest {

    @Test
    @DisplayName("累积式 delta：重复全文只保留一次")
    void cumulativeDuplicate_emitsOnce() {
        String full = "好的，以下是一段 Markdown 内容示例";
        List<StreamToken> out = collect(
                StreamToken.content(full),
                StreamToken.content(full),
                StreamToken.content(full)
        );
        assertThat(out).hasSize(1);
        assertThat(out.get(0).text()).isEqualTo(full);
    }

    @Test
    @DisplayName("累积式 delta：逐帧增长时按增量输出")
    void cumulativeGrowing_emitsIncrements() {
        List<StreamToken> out = collect(
                StreamToken.content("Hello"),
                StreamToken.content("Hello, "),
                StreamToken.content("Hello, world")
        );
        assertThat(out).extracting(StreamToken::text).containsExactly("Hello", ", ", "world");
    }

    @Test
    @DisplayName("增量式 delta：逐 token 输出")
    void incremental_emitsEachToken() {
        List<StreamToken> out = collect(
                StreamToken.content("你"),
                StreamToken.content("好"),
                StreamToken.content("！")
        );
        assertThat(out).extracting(StreamToken::text).containsExactly("你", "好", "！");
    }

    private static List<StreamToken> collect(StreamToken... tokens) {
        List<StreamToken> out = new ArrayList<>();
        StreamDeltaNormalizer.normalizeTokens(reactor.core.publisher.Flux.fromArray(tokens))
                .subscribe(out::add);
        return out;
    }
}
