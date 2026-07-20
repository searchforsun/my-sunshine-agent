package com.sunshine.orchestrator.client;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolManagerClientAssertMayBlockTest {

    @Test
    void assertMayBlock_allowsBoundedElastic() {
        assertThatCode(() -> Mono.fromRunnable(() -> ToolManagerClient.assertMayBlock("test"))
                        .subscribeOn(Schedulers.boundedElastic())
                        .block(Duration.ofSeconds(5)))
                .doesNotThrowAnyException();
    }

    @Test
    void assertMayBlock_rejectsParallelScheduler() {
        assertThatThrownBy(() -> Mono.fromRunnable(() -> ToolManagerClient.assertMayBlock("test"))
                        .subscribeOn(Schedulers.parallel())
                        .block(Duration.ofSeconds(5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-blocking thread")
                .hasMessageContaining("boundedElastic");
    }
}
