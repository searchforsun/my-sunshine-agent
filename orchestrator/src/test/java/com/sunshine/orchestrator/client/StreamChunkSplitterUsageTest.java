package com.sunshine.orchestrator.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StreamChunkSplitterUsageTest {

    @Test
    void usageTokenPassesThroughUnsplit() {
        String json = "{\"type\":\"usage\",\"callSeq\":1,\"inputTokens\":10}";
        StreamToken token = StreamToken.usage(json);

        List<StreamToken> parts = StreamChunkSplitter.splitToken(token, 4);

        assertThat(parts).containsExactly(token);
        assertThat(token.isUsage()).isTrue();
        assertThat(token.text()).isEqualTo(json);
    }
}
